--
-- This file is part of TALER
-- Copyright (C) 2023 Taler Systems SA
--
-- TALER is free software; you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation; either version 3, or (at your option) any later version.
--
-- TALER is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
-- A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- TALER; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
--
-- To Do: comments, although '--' vs 'COMMENT ON' is under discussion.

BEGIN;

SELECT _v.register_patch('nexus-0001', NULL, NULL);

CREATE SCHEMA nexus;
SET search_path TO nexus;

CREATE TYPE taler_amount
  AS
  (val INT8
  ,frac INT4
  );
COMMENT ON TYPE taler_amount
  IS 'Stores an amount, fraction is in units of 1/100000000 of the base value';

CREATE TYPE resource_enum
  AS ENUM ('account', 'connection', 'facade');

CREATE TYPE fetch_level_enum
  AS ENUM ('report', 'statement', 'notification');

CREATE TYPE direction_enum
  AS ENUM ('credit', 'debit');

CREATE TYPE transaction_state_enum
  AS ENUM ('pending', 'booked');

CREATE TYPE ebics_key_state_enum
  AS ENUM ('sent', 'notsent');

-- start of: user management

-- This table accounts the users registered at Nexus
-- without any mention of banking connections.
CREATE TABLE IF NOT EXISTS nexus_logins
  (nexus_login_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,login TEXT NOT NULL PRIMARY KEY
  ,password TEXT NOT NULL
  ,superuser BOOLEAN NOT NULL DEFAULT (false)
  );

COMMENT ON TABLE nexususers
  IS 'xxx';
COMMENT ON COLUMN nexususers.password
  IS 'hashed password - FIXME: which hash, how encoded, salted?';

-- end of: user management

-- start of: connection management


-- This table accounts the bank connections that were
-- created in Nexus and points to their owners.  NO connection
-- configuration details are supposed to exist here.
CREATE TABLE IF NOT EXISTS nexus_bank_connections 
  (connection_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,connection_label TEXT NOT NULL
  ,connection_type TEXT NOT NULL
  ,nexus_login_id BIGINT NOT NULL
    REFERENCES nexus_users(nexus_login_id)
    ON DELETE CASCADE ON UPDATE RESTRICT
  );


-- Details of one EBICS connection.  Each row should point to
-- nexus_bank_connections, where the meta information (like name and type)
-- about the connection is stored.
CREATE TABLE IF NOT EXISTS nexus_ebics_subscribers
  (subscriber_id BIGSERIAL PRIMARY KEY
  ,ebics_url TEXT NOT NULL
  ,host_id TEXT NOT NULL
  ,partner_id TEXT NOT NULL
  ,nexus_login_id BIGINT NOT NULL
    REFERENCES nexus_users(nexus_login_id)
    ON DELETE CASCADE ON UPDATE RESTRICT
  ,system_id TEXT DEFAULT (NULL)
  ,dialect TEXT DEFAULT (NULL)
  ,signature_private_key BYTEA NOT NULL
  ,encryption_private_key BYTEA NOT NULL
  ,authentication_private_key BYTEA NOT NULL
  ,bank_encryption_public_key BYTEA DEFAULT(NULL)
  ,bank_authentication_public_key BYTEA NULL
  ,connection_id BIGINT NOT NULL
    REFERENCES nexus_bank_connections(connection_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ,ebics_ini_state ebics_key_state NOT NULL DEFAULT TO 'notsent'
  ,ebics_hia_state ebics_key_state NOT NULL DEFAULT TO 'notsent'
  );

-- Details of one X-LIBEUFIN-BANK connection.  In other
-- words, each line is one Libeufin-Sandbox user.
CREATE TABLE IF NOT EXISTS xlibeufin_bank_users
  (bank_user_id BIGSERIAL PRIMARY KEY
  ,bank_username TEXT NOT NULL
  ,bank_password TEXT NOT NULL
  ,bank_base_url TEXT NOT NULL
  ,bank_connection_id BIGINT NOT NULL
      REFERENCES nexus_bank_connections(connection_id)
      ON DELETE CASCADE ON UPDATE RESTRICT
  );

-- This table holds the names of the bank accounts as they
-- exist at the bank where the Nexus user has one account.
-- This table participates in the process of 'importing' one
-- bank account.  The importing action has the main goal of
-- providing friendlier names to the Nexus side of one bank
-- account.
CREATE TABLE IF NOT EXISTS offered_bank_accounts 
  (offered_bank_account_id BIGSERIAL PRIMARY KEY
  ,offered_account_id TEXT NOT NULL
  ,connection_id BIGINT NOT NULL
    REFERENCES nexusbankconnections(connection_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,iban TEXT NOT NULL
  ,bank_code TEXT NOT NULL
  ,holder_name TEXT NOT NULL
  ,imported BIGINT DEFAULT(NULL)
    REFERENCES nexus_bank_accounts(account_id)
    ON DELETE RESTRICT
    ON UPDATE RESTRICT
  ,UNIQUE (offered_account_id, connection_id)
  );

-- end of: connection management

-- start of: background tasks

-- Accounts for the background tasks that were created by the user.
CREATE TABLE IF NOT EXISTS nexus_scheduled_tasks 
  (id BIGSERIAL PRIMARY KEY
  ,resource_type resource_enum NOT NULL
  ,resource_id TEXT NOT NULL
  ,task_name TEXT NOT NULL
  ,task_type TEXT NOT NULL
  ,task_cronspec TEXT NOT NULL
  ,task_params TEXT NOT NULL
  ,next_scheduled_execution_sec BIGINT NULL
  ,last_scheduled_execution_sec BIGINT NULL
  );

-- end of: background tasks

-- start of: core banking

-- A bank account managed by Nexus.  Each row corresponds to an
-- actual bank account at the bank and that is owned by the 'account_holder'
-- column.
CREATE TABLE IF NOT EXISTS nexus_bank_accounts
  (nexus_account_id BIGSERIAL PRIMARY KEY
  ,nexus_account_label TEXT NOT NULL UNIQUE
  ,nexus_account_holder TEXT NOT NULL
  ,iban TEXT NOT NULL
  ,bank_code TEXT NOT NULL
  ,default_connection_id BIGINT DEFAULT(NULL)
    REFERENCES nexus_bank_connections(connection_id)
    ON DELETE SET NULL
  ,last_statement_creation_timestamp BIGINT NULL
  ,last_report_creation_timestamp BIGINT NULL
  ,last_notification_creation_timestamp BIGINT NULL
  ,highest_seen_bank_message_serial_id BIGINT NOT NULL
  );

-- start of: facades management

CREATE TABLE IF NOT EXISTS facades 
  (facade_id BIGSERIAL PRIMARY KEY
  ,facade_label TEXT NOT NULL UNIQUE
  ,facace_type TEXT NOT NULL
  ,creator_login_id BIGINT NOT NULL
    REFERENCES nexus_logins(nexus_login_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  );

-- Basic information about the facade state.
CREATE TABLE IF NOT EXISTS wire_gateway_facade_state 
  (wire_gateway_facade_state_id BIGSERIAL PRIMARY KEY
  ,nexus_bank_account BIGINT NOT NULL
    REFERENCES nexus_bank_accounts(nexus_account_id)
  ,connection_id BIGINT NOT NULL
    REFERENCES nexus_bank_connections (connection_id)
  -- Taler maximum is 11 plus 0-terminator
  ,currency VARCHAR(11) NOT NULL
  -- The following column informs whether this facade
  -- wants payment data to come from statements (usually
  -- once a day when the payment is very likely settled),
  -- reports (multiple times a day but the payment might
  -- not be settled). "report" or "statement" or "notification"
  ,reserve_transfer_level TEXT NOT NULL
  ,facade_id BIGINT NOT NULL
    REFERENCES facades(id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  -- The following column points to the last transaction
  -- that was processed already by the facade.  It's used
  -- along the facade-specific ingestion.
  ,highest_seen_message_serial_id BIGINT DEFAULT 0 NOT NULL
  );


-- FIXME: will 'permissions' survive the upcoming Nexus simplification?
CREATE TABLE IF NOT EXISTS nexus_permissions 
  (permission_id BIGSERIAL PRIMARY KEY
  ,resource_type resource_enum NOT NULL
  ,resource_id BIGINT NOT NULL -- comment: references X/Y/Z depending on resource_type
  ,subject_type TEXT NOT NULL -- fixme: enum?
  ,subject_name TEXT NOT NULL -- fixme: bigint?
  ,permission_name TEXT NOT NULL -- fixme: enum!
  ,UNIQUE(resource_type, resource_id, subject_type, subject_name, permission_name)
  );

-- end of: general facades management

-- start of: Taler facade management

-- All the payments that were ingested by Nexus.  Each row
-- points at the Nexus bank account that is related to the transaction.
CREATE TABLE IF NOT EXISTS nexus_bank_transactions 
  (transaction_id BIGSERIAL PRIMARY KEY
  ,account_transaction_id TEXT NOT NULL
  ,nexus_account_account_id NOT NULL
    REFERENCES nexus_bank_accounts(nexus_account_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ,credit_debit_indicator direction_enum NOT NULL
  ,currency TEXT NOT NULL
  ,amount taler_amount NOT NULL
  ,status transaction_state_enum NOT NULL
  ,transaction_json TEXT NOT NULL
  );


-- Holds valid Taler payments, typically those that are returned
-- to the Wirewatch by the Taler facade.
CREATE TABLE IF NOT EXISTS taler_incoming_payments 
  (taler_payment_id BIGSERIAL PRIMARY KEY
  ,transaction_id NOT NULL
    REFERENCES nexus_bank_transactions(transaction_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,reserve_public_key BYTEA CHECK(LENGTH(reserve_public_key)=32)
  ,timestamp BIGINT NOT NULL
  ,incoming_payto_uri TEXT NOT NULL
  );


-- Table holding the data that represent one outgoing payment
-- made by the (user owning the) 'bank_account'.  The 'raw_confirmation'
-- column points at the global table of all the ingested payments
-- where the pointed ingested payment is the confirmation that the
-- pointing payment initiation was finalized at the bank.  All
-- the IDs involved in this table mimic the semantics of ISO20022 pain.001.
CREATE TABLE IF NOT EXISTS payment_initiations
  (payment_initiation_id BIGSERIAL PRIMARY KEY
  ,nexus_bank_account_id BIGINT NOT NULL
    REFERENCES nexus_bank_accounts(nexus_bank_account_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,preparation_date BIGINT NOT NULL
  ,submission_date BIGINT NULL
  ,transaction_sum taler_amount NOT NULL
  ,currency TEXT NOT NULL
  ,end_to_end_id TEXT NOT NULL
  ,payment_information_id TEXT NOT NULL
  ,instruction_id TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,creditor_iban TEXT NOT NULL
  ,creditor_bic TEXT NULL
  ,creditor_name TEXT NOT NULL
  ,submitted BOOLEAN DEFAULT FALSE NOT NULL
  ,invalid BOOLEAN -- does NULL mean _likely_ valid?
  ,message_id TEXT NOT NULL
  ,confirmation_transaction_id BIGINT NULL
    REFERENCES nexus_bank_transactions(transaction_id)
    ON DELETE SET NULL
    ON UPDATE RESTRICT
  );


-- This table holds the outgoing payments that were requested
-- by the exchange to pay merchants.  The columns reflect the
-- data model of the /transfer call from the TWG.
CREATE TABLE IF NOT EXISTS taler_requested_payments 
  (taler_payment_request_id BIGSERIAL PRIMARY KEY
  ,facade_id NOT NULL
    REFERENCES facades(facade_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,payment_initiation_id NOT NULL
    REFERENCES payment_initiations(payment_initiation_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,request_uid TEXT NOT NULL
  ,amount taler_amount NOT NULL -- currency from facade
  ,exchange_base_url TEXT NOT NULL
  ,wtid TEXT NOT NULL
  ,credit_account_payto_uri TEXT NOT NULL
  );


-- Typically contains payments with an invalid reserve public
-- key as the subject.  The 'payment' columns points at the ingested
-- transaction that is invalid in the Taler sense.
CREATE TABLE IF NOT EXISTS taler_invalid_incoming_payments 
  (taler_invalid_incoming_payment_id BIGSERIAL PRIMARY KEY
  ,transaction_id NOT NULL
    REFERENCES nexus_bank_transactions(transaction_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ,timestamp BIGINT NOT NULL
  ,refunded BOOLEAN DEFAULT false NOT NULL
  );

-- end of: Taler facade management

-- start of: Anastasis facade management

CREATE TABLE IF NOT EXISTS anastasis_incoming_payments 
  (anastasis_incoming_payments_id BIGSERIAL PRIMARY KEY
  ,transaction_id NOT NULL
    REFERENCES nexus_bank_transactions(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subject TEXT NOT NULL
  ,timestamp BIGINT NOT NULL
  ,incoming_payto_uri TEXT NOT NULL
  );

-- end of: Anastasis facade management

CREATE TABLE IF NOT EXISTS nexus_bank_messages
  (bank_message_id BIGSERIAL PRIMARY KEY
  ,bank_connection BIGINT NOT NULL
     REFERENCES nexus_bank_connections(connection_id)
     ON DELETE CASCADE
     ON UPDATE RESTRICT
  ,message BYTEA NOT NULL
  ,message_id TEXT NULL
  ,fetch_level fetch_level_enum NOT NULL
  ,errors BOOLEAN DEFAULT FALSE NOT NULL
  );
COMMENT ON TABLE nexus_bank_messages
  IS 'This table holds the business content that came from the
bank.  Storing messages here happens with problematic messages,
or when the storing is enabled.  By default, successful messages
are never stored.'


-- end of: core banking

COMMIT
