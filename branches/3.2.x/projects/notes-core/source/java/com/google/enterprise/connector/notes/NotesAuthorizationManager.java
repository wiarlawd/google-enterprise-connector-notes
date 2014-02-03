// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.enterprise.connector.notes.NotesUserGroupManager.User;
import com.google.enterprise.connector.notes.client.NotesDatabase;
import com.google.enterprise.connector.notes.client.NotesDocument;
import com.google.enterprise.connector.notes.client.NotesSession;
import com.google.enterprise.connector.notes.client.NotesView;
import com.google.enterprise.connector.notes.client.NotesViewNavigator;
import com.google.enterprise.connector.spi.AuthenticationIdentity;
import com.google.enterprise.connector.spi.AuthorizationManager;
import com.google.enterprise.connector.spi.AuthorizationResponse;
import com.google.enterprise.connector.spi.RepositoryException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

class NotesAuthorizationManager implements AuthorizationManager {
  private static final String CLASS_NAME =
      NotesAuthorizationManager.class.getName();
  private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

  private final NotesConnectorSession ncs;

  public NotesAuthorizationManager(NotesConnectorSession session) {
    final String METHOD = "NotesAuthorizationManager";
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "NotesAuthorizationManager being created.");
    ncs = session;
  }

  /* The docid is generated by the application and always takes the format
   * http://server.domain/ReplicaID/0/UniversalDocumentID
   * The protocol is always http://
   *
   * TODO: We need better documentation for the expected docid
   * format here. Plus constants (preferably generated
   * ones). Also, what happens if the docid uses HTTPS?
   *
   * Consider using java.net.URL and String.split to get the
   * pieces. Attachment docid values can be longer.
   */
  protected String getRepIdFromDocId(String docId) {
    int start = docId.indexOf('/', 7);  // Find the first slash after http://
    return docId.substring(start + 1, start + 17);
  }

  /* The docid is generated by the application and always takes the format
   * http://server.domain/ReplicaID/0/UniversalDocumentID
   * The protocol is always http://
   */
  protected String getUNIDFromDocId(String docId) {
    int start = docId.indexOf('/', 7);  // Find the first slash after http://
    return docId.substring(start + 20, start + 52);
  }

  // Explain Lotus Notes Authorization Rules

  // TODO: Add LRU Cache for ALLOW/DENY
  @Override
  @SuppressWarnings("unchecked")
  public Collection<AuthorizationResponse> authorizeDocids(
      Collection<String> docIds, AuthenticationIdentity id) {
    final String METHOD = "authorizeDocids";
    long elapsedTimeMillis = 0;
    long startTime = System.currentTimeMillis();

    ArrayList<AuthorizationResponse> authorized =
        new ArrayList<AuthorizationResponse>(docIds.size());
    try {
      // Find the user in the connector cache.
      String gsaName = ncs.getUsernameType().getUsername(id);
      User user = ncs.getUserGroupManager().getUserByGsaName(gsaName);
      if (user == null) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Person not found in connector user database: " + gsaName +
            " using " + ncs.getUsernameType() + " username type");
        for (String docId : docIds) {
          authorized.add(new AuthorizationResponse(false, docId));
        }
      } else {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Authorizing documents for user " + gsaName +
            " using " + ncs.getUsernameType() + " username type");
        ArrayList<String> userGroups = new ArrayList<String>(user.getGroups());
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Groups for " + gsaName + " are: " + userGroups);

        NotesSession ns = null;
        try {
          ns = ncs.createNotesSession();
          NotesDatabase cdb =
              ns.getDatabase(ncs.getServer(), ncs.getDatabase());
          NotesView securityView = cdb.getView(NCCONST.VIEWSECURITY);
          for (String docId : docIds) {
            NotesViewNavigator secVN = null;
            NotesDocument dbdoc = null;
            try {
              // Extract the database and UNID from the URL
              String repId = getRepIdFromDocId(docId);
              String unid = getUNIDFromDocId(docId);
              LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
                  "Authorizing document: " + repId + " : " + unid);

              // Get the category from the security view for this
              // database. The first document in the category is
              // ALWAYS the database document.
              secVN = securityView.createViewNavFromCategory(repId);
              dbdoc = secVN.getFirstDocument().getDocument();
              boolean dballow =
                  checkDatabaseAccess(dbdoc, user);

              // Only check document level security if we are
              // allowed at the database level. Assume we have
              // access to the document unless proven
              // otherwise...
              boolean docallow = true;
              if (dballow) {
                Collection<String> readers = 
                    ncs.getNotesDocumentManager()
                        .getDocumentReaders(unid, repId);
                if (readers.size() > 0) {
                  docallow = checkDocumentReaders(user, readers, repId);
                } else {
                  LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
                      "No document level security for " + unid);
                }
              }
              boolean allow = docallow && dballow;
              LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
                  "Final auth decision is " + allow + " " + unid);
              authorized.add(new AuthorizationResponse(allow, docId));
            } catch (Throwable t) {
              LOGGER.logp(Level.WARNING, CLASS_NAME, METHOD,
                  "Failed to complete check for: " + docId, t);
              authorized.add(new AuthorizationResponse(
                      AuthorizationResponse.Status.INDETERMINATE, docId));
            } finally {
              Util.recycle(dbdoc);
              Util.recycle(secVN);
              // Log timing for each document.
              if (LOGGER.isLoggable(Level.FINER)) {
                elapsedTimeMillis = System.currentTimeMillis() - startTime;
                LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
                    "ElapsedAuthorizationResponseTime: " + elapsedTimeMillis
                    + " Documents authorized: " + authorized.size());
              }
            }
          }
        } finally {
          ncs.closeNotesSession(ns);
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, CLASS_NAME, e);
    }

    if (LOGGER.isLoggable(Level.FINER)) {
      for (int i = 0; i < authorized.size(); i++) {
        AuthorizationResponse ar = authorized.get(i);
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "AuthorizationResponse: " + ar.getDocid() + " : " + ar.isValid());
      }
    }
    // Get elapsed time in milliseconds
    elapsedTimeMillis = System.currentTimeMillis() - startTime;
    LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
        "TotalAuthorizationResponseTime: " + elapsedTimeMillis
        + " milliseconds.  Documents in batch: " + docIds.size() +
        " Documents authorized: " + authorized.size());
    return authorized;
  }

  protected static String getCommonName(String notesName) {
    if (notesName.startsWith("cn=")) {
      int index = notesName.indexOf('/');
      if (index > 0)
        return notesName.substring(3, index);
    }
    return null;
  }

  /* getDocumentReaders lower-cases the readers list. */
  @VisibleForTesting
  boolean checkDocumentReaders(User user,
      Collection<String> readers, String repId) throws RepositoryException {
    final String METHOD = "checkDocumentReaders";
    LOGGER.entering(CLASS_NAME, METHOD);

    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "Document reader list is " + readers);
    try {
      // Check using the Notes name
      if (readers.contains(user.getNotesName())) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: User is in document readers" + user.getNotesName());
        return true;
      }

      // Check using the common name
      String commonName = getCommonName(user.getNotesName());
      if (readers.contains(commonName)) {
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "ALLOWED: User is in document readers " + commonName);
          return true;
      }

      // Check using groups
      for (String group : user.getGroups()) {
        if (readers.contains(group)) {
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "ALLOWED: Group is in document readers " + group);
          return true;
        }
      }

      // Check using roles
      // Testing with R8.5 roles do not expand to nested groups.
      // You must be a direct member of the group to get the role.
      // TODO: Check and validate this with other versions
      // TODO: If this is true, the UserGroupManager will need to
      // distinguish between direct and indirect group
      // membership.
      for (String role : user.getRolesByDatabase(repId)) {
        if (readers.contains(role)) {
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "ALLOWED: Role is in document readers " + role);
          return true;
        }
      }

      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "DENIED: User's security principals are not in "
          + "document access lists.");
      return false;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  @VisibleForTesting
  boolean checkDatabaseAccess(NotesDocument dbDoc, User user)
      throws RepositoryException {
    final String METHOD = "checkDatabaseAccess";
    LOGGER.entering(CLASS_NAME, METHOD);

    try {
      String commonName = getCommonName(user.getNotesName());
      if (checkDenyUser(dbDoc, user.getNotesName(), commonName)) {
        return false;
      }
      // TODO: why don't we check for deny-by-group?
      if (checkAllowUser(dbDoc, user.getNotesName(), commonName)) {
        return true;
      }
      if (checkAllowGroup(dbDoc, user.getGroups())) {
        return true;
      }
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
    return false;
  }

  // TODO: the access groups may not need to be summary data. to avoid 64k
  @VisibleForTesting
  boolean checkAllowGroup(NotesDocument dbdoc, Collection<String> userGroups)
      throws RepositoryException {
    final String METHOD = "checkAllowGroup";
    LOGGER.entering(CLASS_NAME, METHOD);

    try {
      ArrayList<String> allowGroups =
          toLowerCase(dbdoc.getItemValue(NCCONST.NCITM_DBPERMITGROUPS));
      LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
          "Allow groups are: " + allowGroups.toString());

      for (String group: userGroups) {
        if (allowGroups.contains(group)) {
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "ALLOWED: User is allowed through group " + group);
          return true;
        }
      }
      return false;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  @VisibleForTesting
  boolean checkAllowUser(NotesDocument dbdoc, String... userNames)
      throws RepositoryException {
    final String METHOD = "checkAllowUser";
    LOGGER.entering(CLASS_NAME, METHOD);

    try {
      ArrayList<String> allowList =
          toLowerCase(dbdoc.getItemValue(NCCONST.NCITM_DBPERMITUSERS));
      boolean result = false;
      if (allowList.contains("-default-")) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: -Default- is allowed");
        result = true;
      } else {
        for (String userName : userNames) {
          if (allowList.contains(userName)) {
            LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
                "ALLOWED: User is explictly allowed " + userName);
            result = true;
            break;
          }
        }
      }
      return result;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  @VisibleForTesting
  boolean checkDenyUser(NotesDocument dbdoc, String... userNames)
      throws RepositoryException {
    final String METHOD = "checkDenyUser";
    LOGGER.entering(CLASS_NAME, METHOD);
    try {
      ArrayList<String> denyList =
          toLowerCase(dbdoc.getItemValue(NCCONST.NCITM_DBNOACCESSUSERS));
      for (String userName : userNames) {
        if (denyList.contains(userName)) {
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "DENIED: User is explictly denied " + userName);
          return true;
        }
      }
      return false;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  private ArrayList<String> toLowerCase(Vector<?> items) {
    ArrayList<String> lcList = Lists.newArrayList();
    for (Object item : items) {
      lcList.add(item.toString().toLowerCase());
    }
    return lcList;
  }
}
