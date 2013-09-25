// Copyright 2012 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes;

import com.google.enterprise.connector.notes.client.NotesBase;
import com.google.enterprise.connector.spi.RepositoryException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helpers.
 */
class Util {
  private static final String CLASS_NAME = Util.class.getName();
  private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

  static void recycle(NotesBase obj) {
    if (null != obj) {
      try {
        obj.recycle();
      } catch (RepositoryException e) {
        LOGGER.logp(Level.WARNING, CLASS_NAME, "recycle(obj)",
            "Error calling recycle on Notes object: " + obj, e);
      }
    }
  }

  static void recycle(NotesBase obj, Vector vec) {
    if (null != obj && null != vec) {
      try {
        obj.recycle(vec);
      } catch (RepositoryException e) {
        LOGGER.logp(Level.WARNING, CLASS_NAME, "recycle(obj, vec)",
            "Error calling recycle on Notes object: " + obj
            + " with data: " + vec, e);
      }
    }
  }

  static void close(Statement stmt) {
    if (null != stmt) {
      try {
        stmt.close();
      } catch (SQLException e) {
        LOGGER.logp(Level.WARNING, CLASS_NAME, "close",
            "Error closing statement", e);
      }
    }
  }

  static void close(ResultSet rs) {
    if (null != rs) {
      try {
        rs.close();
      } catch (SQLException e) {
        LOGGER.logp(Level.WARNING, CLASS_NAME, "close",
            "Error closing result set", e);
      }
    }
  }
  
  static void executeStatements(Connection connection, boolean autoCommit,
      String... statements) throws SQLException {
    final String METHOD = "executeStatements";
    if (connection == null) {
      throw new SQLException("Database connection is null");
    }
    Statement stmt = null;
    try {
      stmt = connection.createStatement();
      connection.setAutoCommit(autoCommit);
      connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      for (String statement : statements) {
        stmt.executeUpdate(statement);
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD, "Executed " + statement);
      }
      if (autoCommit == false) {
        try {
          connection.commit();
          LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
              "Committed all transactions successfully");
        } catch (SQLException sqle) {
          connection.rollback();
          LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
              "Rolled back all transactions");
          throw sqle;
        }
      }
    } catch (SQLException e) {
      LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
          "Failed to execute statements", e);
      throw e;
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          LOGGER.logp(Level.WARNING, CLASS_NAME, METHOD, e.getMessage());
        }
      }
    }
  }

  static String buildString(String...args) {
    StringBuilder buf = new StringBuilder();
    for (String arg : args) {
      buf.append(arg);
    }
    return buf.toString();
  }

  /**
   * Private constructor to prevent instantiation.
   */
  private Util() {
  }
}
