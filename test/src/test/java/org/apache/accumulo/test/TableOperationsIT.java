/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.DiskUsage;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.constraints.DefaultKeySizeConstraint;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class TableOperationsIT {

  static TemporaryFolder tempFolder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/target"));
  static final String ROOT = "root";
  static final String ROOT_PASS = "password";

  static MiniAccumuloCluster accumuloCluster;

  static Connector connector;
  static TabletClientService.Client client;
  static AtomicInteger tableCounter;

  @BeforeClass
  public static void startUp() throws IOException, AccumuloException, AccumuloSecurityException, TTransportException, InterruptedException {
    tableCounter = new AtomicInteger(0);
    tempFolder.create();
    accumuloCluster = new MiniAccumuloCluster(tempFolder.getRoot(), ROOT_PASS);

    accumuloCluster.start();

    connector = accumuloCluster.getConnector(ROOT, ROOT_PASS);
  }

  String makeTableName() {
    return "table" + tableCounter.getAndIncrement();
  }

  @Test(timeout = 30 * 1000)
  public void getDiskUsageErrors() throws TableExistsException, AccumuloException, AccumuloSecurityException, TableNotFoundException, TException {
    String tableName = makeTableName();
    connector.tableOperations().create(tableName);
    List<DiskUsage> diskUsage = connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
    assertEquals(1, diskUsage.size());
    assertEquals(0, (long) diskUsage.get(0).getUsage());
    assertEquals(tableName, diskUsage.get(0).getTables().iterator().next());

    connector.securityOperations().revokeTablePermission(ROOT, tableName, TablePermission.READ);
    try {
      connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
      fail("Should throw securityexception");
    } catch (AccumuloSecurityException e) {}

    connector.tableOperations().delete(tableName);
    try {
      connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
      fail("Should throw tablenotfound");
    } catch (TableNotFoundException e) {}
  }

  @Test(timeout = 30 * 1000)
  public void getDiskUsage() throws TableExistsException, AccumuloException, AccumuloSecurityException, TableNotFoundException, TException {

    String tableName = makeTableName();
    connector.tableOperations().create(tableName);

    // verify 0 disk usage
    List<DiskUsage> diskUsages = connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
    assertEquals(1, diskUsages.size());
    assertEquals(1, diskUsages.get(0).getTables().size());
    assertEquals(Long.valueOf(0), diskUsages.get(0).getUsage());
    assertEquals(tableName, diskUsages.get(0).getTables().first());

    // add some data
    BatchWriter bw = connector.createBatchWriter(tableName, new BatchWriterConfig());
    Mutation m = new Mutation("a");
    m.put("b", "c", new Value("abcde".getBytes()));
    bw.addMutation(m);
    bw.flush();
    bw.close();

    connector.tableOperations().compact(tableName, new Text("A"), new Text("z"), true, true);

    // verify we have usage
    diskUsages = connector.tableOperations().getDiskUsage(Collections.singleton(tableName));
    assertEquals(1, diskUsages.size());
    assertEquals(1, diskUsages.get(0).getTables().size());
    assertTrue(diskUsages.get(0).getUsage() > 0);
    assertEquals(tableName, diskUsages.get(0).getTables().first());

    String newTable = makeTableName();

    // clone table
    connector.tableOperations().clone(tableName, newTable, false, null, null);

    // verify tables are exactly the same
    Set<String> tables = new HashSet<String>();
    tables.add(tableName);
    tables.add(newTable);
    diskUsages = connector.tableOperations().getDiskUsage(tables);
    assertEquals(1, diskUsages.size());
    assertEquals(2, diskUsages.get(0).getTables().size());
    assertTrue(diskUsages.get(0).getUsage() > 0);

    connector.tableOperations().compact(tableName, new Text("A"), new Text("z"), true, true);
    connector.tableOperations().compact(newTable, new Text("A"), new Text("z"), true, true);

    // verify tables have differences
    diskUsages = connector.tableOperations().getDiskUsage(tables);
    assertEquals(2, diskUsages.size());
    assertEquals(1, diskUsages.get(0).getTables().size());
    assertEquals(1, diskUsages.get(1).getTables().size());
    assertTrue(diskUsages.get(0).getUsage() > 0);
    assertTrue(diskUsages.get(1).getUsage() > 0);

    connector.tableOperations().delete(tableName);
  }

  @Test(timeout = 30 * 1000)
  public void createTable() throws TableExistsException, AccumuloException, AccumuloSecurityException, TableNotFoundException {
    String tableName = makeTableName();
    connector.tableOperations().create(tableName);
    Iterable<Map.Entry<String,String>> itrProps = connector.tableOperations().getProperties(tableName);
    Map<String,String> props = propsToMap(itrProps);
    assertEquals(DefaultKeySizeConstraint.class.getName(), props.get(Property.TABLE_CONSTRAINT_PREFIX.toString() + "1"));
    connector.tableOperations().delete(tableName);
  }

  @Test(timeout = 30 * 1000)
  public void createMergeClonedTable() throws Exception {
    String originalTable = makeTableName();
    TableOperations tops = connector.tableOperations();

    TreeSet<Text> splits = Sets.newTreeSet(Arrays.asList(new Text("a"), new Text("b"), new Text("c"), new Text("d")));

    tops.create(originalTable);
    tops.addSplits(originalTable, splits);

    BatchWriter bw = connector.createBatchWriter(originalTable, new BatchWriterConfig());
    for (Text row : splits) {
      Mutation m = new Mutation(row);
      for (int i = 0; i < 10; i++) {
        for (int j = 0; j < 10; j++) {
          m.put(Integer.toString(i), Integer.toString(j), Integer.toString(i + j));
        }
      }

      bw.addMutation(m);
    }

    bw.close();

    String clonedTable = makeTableName();

    tops.clone(originalTable, clonedTable, true, null, null);
    tops.merge(clonedTable, null, new Text("b"));

    Map<String,Integer> rowCounts = Maps.newHashMap();
    Scanner s = connector.createScanner(clonedTable, new Authorizations());
    for (Entry<Key,Value> entry : s) {
      final Key key = entry.getKey();
      String row = key.getRow().toString();
      String cf = key.getColumnFamily().toString(), cq = key.getColumnQualifier().toString();
      String value = entry.getValue().toString();

      if (rowCounts.containsKey(row)) {
        rowCounts.put(row, rowCounts.get(row) + 1);
      } else {
        rowCounts.put(row, 1);
      }

      Assert.assertEquals(Integer.parseInt(cf) + Integer.parseInt(cq), Integer.parseInt(value));
    }

    Collection<Text> clonedSplits = tops.listSplits(clonedTable);
    Set<Text> expectedSplits = Sets.newHashSet(new Text("b"), new Text("c"), new Text("d"));
    for (Text clonedSplit : clonedSplits) {
      Assert.assertTrue("Encountered unexpected split on the cloned table: " + clonedSplit, expectedSplits.remove(clonedSplit));
    }

    Assert.assertTrue("Did not find all expected splits on the cloned table: " + expectedSplits, expectedSplits.isEmpty());
  }

  private Map<String,String> propsToMap(Iterable<Map.Entry<String,String>> props) {
    Map<String,String> map = new HashMap<String,String>();
    for (Map.Entry<String,String> prop : props) {
      map.put(prop.getKey(), prop.getValue());
    }
    return map;
  }

  @AfterClass
  public static void shutDown() throws IOException, InterruptedException {
    accumuloCluster.stop();
    tempFolder.delete();
  }
}
