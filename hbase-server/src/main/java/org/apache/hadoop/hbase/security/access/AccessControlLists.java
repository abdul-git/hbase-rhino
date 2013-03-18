/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.security.access;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.exceptions.TableNotDisabledException;
import org.apache.hadoop.hbase.exceptions.TableNotEnabledException;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableDescriptors;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AccessControlProtos;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.Text;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Maintains lists of permission grants to users and groups to allow for
 * authorization checks by {@link AccessController}.
 *
 * <p>
 * Access control lists are stored in an "internal" metadata table named
 * {@code _acl_}. Each table's permission grants are stored as a separate row,
 * keyed by the table name. KeyValues for permissions assignments are stored
 * in one of the formats:
 * <pre>
 * Key                      Desc
 * --------                 --------
 * user                     table level permissions for a user [R=read, W=write]
 * group                    table level permissions for a group
 * user,family              column family level permissions for a user
 * group,family             column family level permissions for a group
 * user,family,qualifier    column qualifier level permissions for a user
 * group,family,qualifier   column qualifier level permissions for a group
 * </pre>
 * All values are encoded as byte arrays containing the codes from the
 * org.apache.hadoop.hbase.security.access.TablePermission.Action enum.
 * </p>
 */
public class AccessControlLists {
  /** Internal storage table for access control lists */
  public static final String ACL_TABLE_NAME_STR = "_acl_";
  public static final byte[] ACL_TABLE_NAME = Bytes.toBytes(ACL_TABLE_NAME_STR);
  public static final byte[] ACL_GLOBAL_NAME = ACL_TABLE_NAME;
  /** Column family used to store ACL grants */
  public static final String ACL_LIST_FAMILY_STR = "l";
  public static final byte[] ACL_LIST_FAMILY = Bytes.toBytes(ACL_LIST_FAMILY_STR);
  /** name of shadow CF for access control lists */
  public final static byte[] ACL_CF_NAME = ACL_TABLE_NAME;

  /** Table descriptor for ACL internal table */
  public static final HTableDescriptor ACL_TABLEDESC = new HTableDescriptor(ACL_TABLE_NAME);
  static {
    ACL_TABLEDESC.addFamily(new HColumnDescriptor(ACL_LIST_FAMILY)
      .setBlockCacheEnabled(true)
      .setBlocksize(8*1024)
      .setCacheDataOnWrite(true)
      .setCacheIndexesOnWrite(true)
      .setInMemory(true)
      .setMaxVersions(10) // Keep some versions to aid in debugging
      .setScope(HConstants.REPLICATION_SCOPE_LOCAL)
      .setTimeToLive(HConstants.FOREVER));
  }

  /** Column descriptor for internal shadow ACL column */
  public static final HColumnDescriptor ACL_COLUMNDESC = new HColumnDescriptor(ACL_CF_NAME)
    .setBlockCacheEnabled(true)
    .setBlocksize(8*1024)
    .setBloomFilterType(BloomType.ROWCOL)
    .setCacheBloomsOnWrite(true)
    .setCacheIndexesOnWrite(true)
    .setMaxVersions(10) // Keep some versions to aid in debugging
    .setScope(HConstants.REPLICATION_SCOPE_LOCAL)
    .setTimeToLive(HConstants.FOREVER);

  /**
   * Delimiter to separate user, column family, and qualifier in
   * _acl_ table info: column keys */
  public static final char ACL_KEY_DELIMITER = ',';
  /** Prefix character to denote group names */
  public static final String GROUP_PREFIX = "@";
  /** Configuration key for superusers */
  public static final String SUPERUSER_CONF_KEY = "hbase.superuser";

  private static Log LOG = LogFactory.getLog(AccessControlLists.class);

  /**
   * Check for existence of {@code _acl_} table and create it if it does not exist
   * @param master reference to HMaster
   */
  static void init(MasterServices master) throws IOException {
    // For each table, insure the ACL shadow CF exists. If not, create it.
    TableDescriptors tables = master.getTableDescriptors();
    for (HTableDescriptor desc: tables.getAll().values()) {
      if (!desc.hasFamily(ACL_CF_NAME)) {
        // XXX: This can only happen once, if security is enabled on a cluster
        // that did not have it previously. We issue instructions to disable
        // the table, alter it, and reenable it (if it was enabled). This is
        // unsatisfactory because we will be racing with any number of other
        // asynchronous processes in the master, but other alternatives for
        // aborting pending opens will be worse. Here we act like an admin
        // client undertaking a schema update. For a very large table the
        // number of retries below may not be sufficient. Blindly retrying
        // like this is inelegant to put it mildly. Perhaps we can introduce
        // an AssignmentManager API like waitForAllUnassigned(List<HRI>) and
        // waitForAllAssigned(List<HRI>). Anyway, we can document that the
        // creation of shadow CFs for ACLs on existing tables should be
        // created by an admin (or migration tool) before shutting down to
        // restart with security enabled, to avoid executing the below at
        // runtime.
        LOG.debug("Adding ACL metacolumn to table " + desc.getNameAsString());
        boolean enabled = true;
        try {
          master.disableTable(desc.getName());
        } catch (TableNotEnabledException e) {
          enabled = false;
        }
        int retries = 30;
        do {
          try {
            Thread.sleep(5000);
            master.addColumn(desc.getName(), ACL_COLUMNDESC);
            break;
          } catch (InterruptedException e) {
            Thread.interrupted();
            break;
          } catch (TableNotDisabledException e) {
            // retry
          }
        } while (--retries > 0);
        if (enabled) {
          master.enableTable(desc.getName());
        }
        LOG.debug("Completed adding ACL metacolumn to table " +
          desc.getNameAsString());
      }
    }
    // Create the ACL table if it does not exist yet
    if (!MetaReader.tableExists(master.getCatalogTracker(), ACL_TABLE_NAME_STR)) {
      master.createTable(ACL_TABLEDESC, null);
    }
  }

  /**
   * Stores a new user permission grant in the access control lists table.
   * @param conf the configuration
   * @param userPerm the details of the permission to be granted
   * @throws IOException in the case of an error accessing the metadata table
   */
  static void addUserPermission(Configuration conf, UserPermission userPerm)
      throws IOException {
    Permission.Action[] actions = userPerm.getActions();

    Put p = new Put(userPerm.isGlobal() ? ACL_GLOBAL_NAME : userPerm.getTable());
    byte[] key = userPermissionKey(userPerm);

    if ((actions == null) || (actions.length == 0)) {
      LOG.warn("No actions associated with user '"+Bytes.toString(userPerm.getUser())+"'");
      return;
    }

    byte[] value = new byte[actions.length];
    for (int i = 0; i < actions.length; i++) {
      value[i] = actions[i].code();
    }
    p.add(ACL_LIST_FAMILY, key, value);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Writing permission for table "+
          Bytes.toString(userPerm.getTable())+" "+
          Bytes.toString(key)+": "+Bytes.toStringBinary(value)
      );
    }
    HTable acls = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);
      acls.put(p);
    } finally {
      if (acls != null) acls.close();
    }
  }

  /**
   * Removes a previously granted permission from the stored access control
   * lists.  The {@link TablePermission} being removed must exactly match what
   * is stored -- no wildcard matching is attempted.  Ie, if user "bob" has
   * been granted "READ" access to the "data" table, but only to column family
   * plus qualifier "info:colA", then trying to call this method with only
   * user "bob" and the table name "data" (but without specifying the
   * column qualifier "info:colA") will have no effect.
   *
   * @param conf the configuration
   * @param userPerm the details of the permission to be revoked
   * @throws IOException if there is an error accessing the metadata table
   */
  static void removeUserPermission(Configuration conf, UserPermission userPerm)
      throws IOException {

    Delete d = new Delete(userPerm.isGlobal() ? ACL_GLOBAL_NAME : userPerm.getTable());
    byte[] key = userPermissionKey(userPerm);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing permission "+ userPerm.toString());
    }
    d.deleteColumns(ACL_LIST_FAMILY, key);
    HTable acls = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);
      acls.delete(d);
    } finally {
      if (acls != null) acls.close();
    }
  }

  /**
   * Remove specified table from the _acl_ table.
   */
  static void removeTablePermissions(Configuration conf, byte[] tableName)
      throws IOException{
    Delete d = new Delete(tableName);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing permissions of removed table "+ Bytes.toString(tableName));
    }

    HTable acls = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);
      acls.delete(d);
    } finally {
      if (acls != null) acls.close();
    }
  }

  /**
   * Remove specified table column from the _acl_ table.
   */
  static void removeTablePermissions(Configuration conf, byte[] tableName, byte[] column)
      throws IOException{

    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing permissions of removed column " + Bytes.toString(column) +
                " from table "+ Bytes.toString(tableName));
    }

    HTable acls = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);

      Scan scan = new Scan();
      scan.addFamily(ACL_LIST_FAMILY);

      String columnName = Bytes.toString(column);
      scan.setFilter(new QualifierFilter(CompareOp.EQUAL, new RegexStringComparator(
                     String.format("(%s%s%s)|(%s%s)$",
                     ACL_KEY_DELIMITER, columnName, ACL_KEY_DELIMITER,
                     ACL_KEY_DELIMITER, columnName))));

      Set<byte[]> qualifierSet = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
      ResultScanner scanner = acls.getScanner(scan);
      try {
        for (Result res : scanner) {
          for (byte[] q : res.getFamilyMap(ACL_LIST_FAMILY).navigableKeySet()) {
            qualifierSet.add(q);
          }
        }
      } finally {
        scanner.close();
      }

      if (qualifierSet.size() > 0) {
        Delete d = new Delete(tableName);
        for (byte[] qualifier : qualifierSet) {
          d.deleteColumns(ACL_LIST_FAMILY, qualifier);
        }
        acls.delete(d);
      }
    } finally {
      if (acls != null) acls.close();
    }
  }

  /**
   * Build qualifier key from user permission:
   *  username
   *  username,family
   *  username,family,qualifier
   */
  static byte[] userPermissionKey(UserPermission userPerm) {
    byte[] qualifier = userPerm.getQualifier();
    byte[] family = userPerm.getFamily();
    byte[] key = userPerm.getUser();

    if (family != null && family.length > 0) {
      key = Bytes.add(key, Bytes.add(new byte[]{ACL_KEY_DELIMITER}, family));
      if (qualifier != null && qualifier.length > 0) {
        key = Bytes.add(key, Bytes.add(new byte[]{ACL_KEY_DELIMITER}, qualifier));
      }
    }

    return key;
  }

  /**
   * Returns {@code true} if the given region is part of the {@code _acl_}
   * metadata table.
   */
  static boolean isAclRegion(HRegion region) {
    return Bytes.equals(ACL_TABLE_NAME, region.getTableDesc().getName());
  }

  /**
   * Returns {@code true} if the given table is {@code _acl_} metadata table.
   */
  static boolean isAclTable(HTableDescriptor desc) {
    return Bytes.equals(ACL_TABLE_NAME, desc.getName());
  }

  /**
   * Loads all of the permission grants stored in a region of the {@code _acl_}
   * table.
   *
   * @param aclRegion
   * @return a map of the permissions for this table.
   * @throws IOException
   */
  static Map<byte[],ListMultimap<String,TablePermission>> loadAll(
      HRegion aclRegion)
    throws IOException {

    if (!isAclRegion(aclRegion)) {
      throw new IOException("Can only load permissions from "+ACL_TABLE_NAME_STR);
    }

    Map<byte[],ListMultimap<String,TablePermission>> allPerms =
        new TreeMap<byte[],ListMultimap<String,TablePermission>>(Bytes.BYTES_COMPARATOR);

    // do a full scan of _acl_ table

    Scan scan = new Scan();
    scan.addFamily(ACL_LIST_FAMILY);

    InternalScanner iScanner = null;
    try {
      iScanner = aclRegion.getScanner(scan);

      while (true) {
        List<KeyValue> row = new ArrayList<KeyValue>();

        boolean hasNext = iScanner.next(row);
        ListMultimap<String,TablePermission> perms = ArrayListMultimap.create();
        byte[] table = null;
        for (KeyValue kv : row) {
          if (table == null) {
            table = kv.getRow();
          }
          Pair<String,TablePermission> permissionsOfUserOnTable =
              parseTablePermissionRecord(table, kv);
          if (permissionsOfUserOnTable != null) {
            String username = permissionsOfUserOnTable.getFirst();
            TablePermission permissions = permissionsOfUserOnTable.getSecond();
            perms.put(username, permissions);
          }
        }
        if (table != null) {
          allPerms.put(table, perms);
        }
        if (!hasNext) {
          break;
        }
      }
    } finally {
      if (iScanner != null) {
        iScanner.close();
      }
    }

    return allPerms;
  }

  /**
   * Load all permissions from the region server holding {@code _acl_},
   * primarily intended for testing purposes.
   */
  static Map<byte[],ListMultimap<String,TablePermission>> loadAll(
      Configuration conf) throws IOException {
    Map<byte[],ListMultimap<String,TablePermission>> allPerms =
        new TreeMap<byte[],ListMultimap<String,TablePermission>>(Bytes.BYTES_COMPARATOR);

    // do a full scan of _acl_, filtering on only first table region rows

    Scan scan = new Scan();
    scan.addFamily(ACL_LIST_FAMILY);

    HTable acls = null;
    ResultScanner scanner = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);
      scanner = acls.getScanner(scan);
      for (Result row : scanner) {
        ListMultimap<String,TablePermission> resultPerms =
            parseTablePermissions(row.getRow(), row);
        allPerms.put(row.getRow(), resultPerms);
      }
    } finally {
      if (scanner != null) scanner.close();
      if (acls != null) acls.close();
    }

    return allPerms;
  }

  /**
   * Reads user permission assignments stored in the <code>l:</code> column
   * family of the first table row in <code>_acl_</code>.
   *
   * <p>
   * See {@link AccessControlLists class documentation} for the key structure
   * used for storage.
   * </p>
   */
  static ListMultimap<String, TablePermission> getTablePermissions(Configuration conf,
      byte[] tableName) throws IOException {
    if (tableName == null) tableName = ACL_TABLE_NAME;

    // for normal user tables, we just read the table row from _acl_
    ListMultimap<String, TablePermission> perms = ArrayListMultimap.create();
    HTable acls = null;
    try {
      acls = new HTable(conf, ACL_TABLE_NAME);
      Get get = new Get(tableName);
      get.addFamily(ACL_LIST_FAMILY);
      Result row = acls.get(get);
      if (!row.isEmpty()) {
        perms = parseTablePermissions(tableName, row);
      } else {
        LOG.info("No permissions found in " + ACL_TABLE_NAME_STR + " for table "
            + Bytes.toString(tableName));
      }
    } finally {
      if (acls != null) acls.close();
    }

    return perms;
  }

  /**
   * Returns the currently granted permissions for a given table as a list of
   * user plus associated permissions.
   */
  static List<UserPermission> getUserPermissions(
      Configuration conf, byte[] tableName)
  throws IOException {
    ListMultimap<String,TablePermission> allPerms = getTablePermissions(
      conf, tableName);

    List<UserPermission> perms = new ArrayList<UserPermission>();

    for (Map.Entry<String, TablePermission> entry : allPerms.entries()) {
      UserPermission up = new UserPermission(Bytes.toBytes(entry.getKey()),
          entry.getValue().getTable(), entry.getValue().getFamily(),
          entry.getValue().getQualifier(), entry.getValue().getActions());
      perms.add(up);
    }
    return perms;
  }

  private static ListMultimap<String,TablePermission> parseTablePermissions(
      byte[] table, Result result) {
    ListMultimap<String,TablePermission> perms = ArrayListMultimap.create();
    if (result != null && result.size() > 0) {
      for (KeyValue kv : result.raw()) {

        Pair<String,TablePermission> permissionsOfUserOnTable =
            parseTablePermissionRecord(table, kv);

        if (permissionsOfUserOnTable != null) {
          String username = permissionsOfUserOnTable.getFirst();
          TablePermission permissions = permissionsOfUserOnTable.getSecond();
          perms.put(username, permissions);
        }
      }
    }
    return perms;
  }

  private static Pair<String,TablePermission> parseTablePermissionRecord(
      byte[] table, KeyValue kv) {
    // return X given a set of permissions encoded in the permissionRecord kv.
    byte[] family = kv.getFamily();

    if (!Bytes.equals(family, ACL_LIST_FAMILY)) {
      return null;
    }

    byte[] key = kv.getQualifier();
    byte[] value = kv.getValue();
    if (LOG.isTraceEnabled()) {
      LOG.trace("Read acl: kv ["+
                Bytes.toStringBinary(key)+": "+
                Bytes.toStringBinary(value)+"]");
    }

    // check for a column family appended to the key
    // TODO: avoid the string conversion to make this more efficient
    String username = Bytes.toString(key);
    int idx = username.indexOf(ACL_KEY_DELIMITER);
    byte[] permFamily = null;
    byte[] permQualifier = null;
    if (idx > 0 && idx < username.length()-1) {
      String remainder = username.substring(idx+1);
      username = username.substring(0, idx);
      idx = remainder.indexOf(ACL_KEY_DELIMITER);
      if (idx > 0 && idx < remainder.length()-1) {
        permFamily = Bytes.toBytes(remainder.substring(0, idx));
        permQualifier = Bytes.toBytes(remainder.substring(idx+1));
      } else {
        permFamily = Bytes.toBytes(remainder);
      }
    }

    return new Pair<String,TablePermission>(
        username, new TablePermission(table, permFamily, permQualifier, value));
  }

  /**
   * Writes a set of permissions as {@link org.apache.hadoop.io.Writable} instances
   * and returns the resulting byte array.
   *
   * Writes a set of permission [user: table permission]
   */
  public static byte[] writePermissionsAsBytes(ListMultimap<String, TablePermission> perms,
      Configuration conf) {
    return ProtobufUtil.prependPBMagic(ProtobufUtil.toUserTablePermissions(perms).toByteArray());
  }

  /**
   * Reads a set of permissions as {@link org.apache.hadoop.io.Writable} instances
   * from the input stream.
   */
  public static ListMultimap<String, TablePermission> readPermissions(byte[] data,
      Configuration conf)
  throws DeserializationException {
    if (ProtobufUtil.isPBMagicPrefix(data)) {
      int pblen = ProtobufUtil.lengthOfPBMagic();
      try {
        AccessControlProtos.UserTablePermissions perms =
          AccessControlProtos.UserTablePermissions.newBuilder().mergeFrom(
            data, pblen, data.length - pblen).build();
        return ProtobufUtil.toUserTablePermissions(perms);
      } catch (InvalidProtocolBufferException e) {
        throw new DeserializationException(e);
      }
    } else {
      ListMultimap<String,TablePermission> perms = ArrayListMultimap.create();
      try {
        DataInput in = new DataInputStream(new ByteArrayInputStream(data));
        int length = in.readInt();
        for (int i=0; i<length; i++) {
          String user = Text.readString(in);
          List<TablePermission> userPerms =
            (List)HbaseObjectWritableFor96Migration.readObject(in, conf);
          perms.putAll(user, userPerms);
        }
      } catch (IOException e) {
        throw new DeserializationException(e);
      }
      return perms;
    }
  }

  /**
   * Returns whether or not the given name should be interpreted as a group
   * principal.  Currently this simply checks if the name starts with the
   * special group prefix character ("@").
   */
  public static boolean isGroupPrincipal(String name) {
    return name != null && name.startsWith(GROUP_PREFIX);
  }

  /**
   * Returns the actual name for a group principal (stripped of the
   * group prefix).
   */
  public static String getGroupName(String aclKey) {
    if (!isGroupPrincipal(aclKey)) {
      return aclKey;
    }

    return aclKey.substring(GROUP_PREFIX.length());
  }

  /**
   * Return the qualifier for the ACL in the shadow CF for the given KeyValue
   */
  public static byte[] getQualifierFor(Cell cell) {
    byte[] bytes = new byte[cell.getFamilyLength() + cell.getQualifierLength()];
    int pos = Bytes.putBytes(bytes, 0, cell.getFamilyArray(), cell.getFamilyOffset(),
      cell.getFamilyLength());
    pos = Bytes.putBytes(bytes, pos, cell.getQualifierArray(), cell.getQualifierOffset(),
      cell.getQualifierLength());
    return bytes;
  }
}
