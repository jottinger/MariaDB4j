/*
 * Copyright (c) 2012 Michael Vorburger
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * See also http://www.apache.org/licenses/LICENSE-2.0.html for an
 * explanation of the license and how it is applied.
 */
package ch.vorburger.mariadb4j;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Main case that provides capability to install, start, and use an embedded database
 * @author Michael Vorburger
 * @author Michael Seaton
 */
public class DB {

	private static final Logger logger = LoggerFactory.getLogger(DB.class);

	protected final Configuration config;

	private ManagedProcess mysqldProcess;

	private DB(Configuration config) {
		this.config = config;
	}

	/**
	 * This factory method is the mechanism for constructing a new embedded database for use
	 * This method automatically installs the database and prepares it for use
	 * @param config Configuration of the embedded instance
	 * @return a new DB instance
	 * @throws Exception
	 */
	public static DB newEmbeddedDB(Configuration config) {
		DB db = new DB(config);
		db.unpackEmbeddedDb();
		db.prepareDataDirectory();
		db.install();
		return db;
	}

	/**
	 * Installs the database to the location specified in the configuration
	 */
	protected void install() {
		logger.info("Installing a new embedded database to: " + getBaseDir());
		try {
			ManagedProcessBuilder builder = new ManagedProcessBuilder(config.getBaseDir() + "/bin/mysql_install_db");
			builder.addFileArgument("--datadir", getDataDir()).setWorkingDirectory(getBaseDir());
			if (SystemUtils.IS_OS_LINUX) {
				builder.addFileArgument("--basedir", getBaseDir());
				builder.addArgument("--no-defaults");
				builder.addArgument("--force");
				builder.addArgument("--skip-name-resolve");
				builder.addArgument("--verbose");
			}
			ManagedProcess mysqlInstallProcess = builder.build();
			mysqlInstallProcess.start();
			mysqlInstallProcess.waitForExit();
		}
		catch (Exception e) {
			throw new ManagedProcessException("An error occurred while installing the database", e);
		}
		logger.info("Installation complete.");
	}

	/**
	 * Starts up the database, using the data directory and port specified in the configuration
	 */
	public void start() {
		logger.info("Starting up the database...");
		try {
			ManagedProcessBuilder builder = new ManagedProcessBuilder(config.getBaseDir() + "/bin/mysqld");
			builder.addArgument("--no-defaults");  // *** THIS MUST COME FIRST ***
			builder.addArgument("--console");
			builder.addFileArgument("--basedir", Util.getDirectory(config.getBaseDir()));
			builder.addFileArgument("--datadir", Util.getDirectory(config.getDataDir()));
			builder.addArgument("--port="+config.getPort());
			mysqldProcess = builder.build();
			mysqldProcess.start();
			mysqldProcess.waitForConsoleMessage("mysqld: ready for connections.");
			mysqldProcess.setDestroyOnShutdown(true);
			cleanupOnExit();
		}
		catch (Exception e) {
			throw new ManagedProcessException("An error occurred while starting the database", e);
		}
		logger.info("Database startup complete.");
	}

	/**
	 * Stops the database
	 */
	public void stop() {
		logger.info("Stopping the database...");
		if (mysqldProcess.isAlive()) {
			mysqldProcess.destroy();
			logger.info("Database stopped.");
		}
		else {
			logger.info("Database was already stopped.");
		}
	}

	/**
	 * Based on the current OS, unpacks the appropriate version of MariaDB to the
	 * file system based on the configuration
	 */
	protected void unpackEmbeddedDb() {
		logger.info("Unpacking the embedded database...");
		StringBuilder source = new StringBuilder();
		source.append(getClass().getPackage().getName().replace(".", "/"));
		source.append("/").append(config.getDatabaseVersion()).append("/");
		source.append(SystemUtils.IS_OS_WINDOWS ? "win32" : "linux");

		File destination = Util.getDirectory(config.getBaseDir());
		try {
			Util.extractFromClasspathToFile(source.toString(), destination);
			if (SystemUtils.IS_OS_LINUX) {
				Util.forceExecutable(new File(destination, "bin/my_print_defaults"));
				Util.forceExecutable(new File(destination, "bin/mysql_install_db"));
				Util.forceExecutable(new File(destination, "bin/mysqld"));
			}
		}
		catch (IOException e) {
			throw new RuntimeException("Error unpacking embedded db", e);
		}
		logger.info("Database successfully unpacked to " + destination);
	}

	/**
	 * If the data directory specified in the configuration is a temporary directory,
	 * this deletes any previous version.  It also makes sure that the directory exists.
	 */
	protected void prepareDataDirectory() {
		logger.info("Preparing data directory...");
		try {
			if (Util.isTemporaryDirectory(config.getDataDir())) {
				FileUtils.deleteDirectory(new File(config.getDataDir()));
			}
			getDataDir();
		}
		catch (Exception e) {
			throw new ManagedProcessException("An error occurred while preparing the data directory", e);
		}
		logger.info("Data directory prepared.");
	}

	/**
	 * Adds a shutdown hook to ensure that when the JVM exits, the database is stopped, and any
	 * temporary data directories are cleaned up.
	 */
	protected void cleanupOnExit() {
		String threadName = "Shutdown Hook Deletion Thread for Temporary DB " + config.getDataDir();
		final DB db = this;
		Runtime.getRuntime().addShutdownHook(new Thread(threadName) {
			@Override
			public void run() {
				try {
					db.stop();
				}
				catch (ManagedProcessException e) {
					logger.info("An error occurred while stopping the database", e);
				}
				try {
					if (Util.isTemporaryDirectory(config.getDataDir())) {
						FileUtils.deleteDirectory(getDataDir());
					}
				}
				catch (IOException e) {
					logger.info("An error occurred while deleting the data directory", e);
				}
			}
		});
	}

	/**
	 * @return convenience method to return the base directory of the database
	 */
	protected File getBaseDir() {
		return Util.getDirectory(config.getBaseDir());
	}

	/**
	 * @return convenience method to return the data directory of the database
	 */
	protected File getDataDir() {
		return Util.getDirectory(config.getDataDir());
	}
}
