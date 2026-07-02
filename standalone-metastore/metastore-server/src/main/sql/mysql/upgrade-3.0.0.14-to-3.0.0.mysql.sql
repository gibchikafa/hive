SELECT 'Upgrading MetaStore schema from 3.0.0.14 to 3.0.0' AS MESSAGE;

-- Bridge from the Hopsworks-specific version 3.0.0.14 to the standard Apache Hive 3.0.0
-- version string so that the standard upgrade chain (3.0.0 -> 3.1.0 -> ... -> 4.1.0) can
-- proceed. The 3.0.0.14 Hopsworks schema contains no DDL changes that overlap with the
-- subsequent standard upgrade scripts, so no DDL is needed here.
UPDATE VERSION SET SCHEMA_VERSION='3.0.0', VERSION_COMMENT='Hive release version 3.0.0' WHERE VER_ID=1;

SELECT 'Finished upgrading MetaStore schema from 3.0.0.14 to 3.0.0' AS MESSAGE;
