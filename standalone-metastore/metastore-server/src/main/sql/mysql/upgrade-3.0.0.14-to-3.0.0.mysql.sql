SELECT 'Upgrading MetaStore schema from 3.0.0.14 to 3.0.0' AS MESSAGE;

-- Bridge from the Hopsworks-specific version 3.0.0.14 to the standard Apache Hive 3.0.0
-- version string so that the standard upgrade chain (3.0.0 -> 3.1.0 -> ... -> 4.1.0) can
-- proceed, and converge the Hopsworks schema divergences that the standard scripts and
-- the upstream JDO mappings assume are present.

-- The Hopsworks 3.0 schema replaced string locations with SDS/inode references:
-- CTLGS.LOCATION_URI and DBS.DB_LOCATION_URI do not exist, but upstream MCatalog and
-- MDatabase select them. Restore them; the catalog placeholder 'TBD' is replaced with
-- the warehouse root by HMSHandler.createDefaultCatalog on first startup, and database
-- locations are backfilled from the SDS rows the Hopsworks schema links via DBS.SD_ID.
ALTER TABLE CTLGS ADD LOCATION_URI varchar(4000) CHARACTER SET latin1 COLLATE latin1_general_cs NOT NULL DEFAULT 'TBD';
ALTER TABLE DBS ADD DB_LOCATION_URI varchar(4000) CHARACTER SET latin1 COLLATE latin1_bin;
UPDATE DBS D JOIN SDS S ON D.SD_ID = S.SD_ID SET D.DB_LOCATION_URI = S.LOCATION;
ALTER TABLE DBS MODIFY DB_LOCATION_URI varchar(4000) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL;

-- The Hopsworks 3.0 schema dropped the delegation token store tables, but the upstream
-- MMasterKey/MDelegationToken models (used when the metastore token store is DB-backed)
-- expect them.
CREATE TABLE IF NOT EXISTS `MASTER_KEYS` (
  `KEY_ID` INTEGER NOT NULL AUTO_INCREMENT,
  `MASTER_KEY` VARCHAR(767) BINARY NULL,
  PRIMARY KEY (`KEY_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE IF NOT EXISTS `DELEGATION_TOKENS` (
  `TOKEN_IDENT` VARCHAR(767) BINARY NOT NULL,
  `TOKEN` VARCHAR(767) BINARY NULL,
  PRIMARY KEY (`TOKEN_IDENT`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

UPDATE VERSION SET SCHEMA_VERSION='3.0.0', VERSION_COMMENT='Hive release version 3.0.0' WHERE VER_ID=1;

SELECT 'Finished upgrading MetaStore schema from 3.0.0.14 to 3.0.0' AS MESSAGE;
