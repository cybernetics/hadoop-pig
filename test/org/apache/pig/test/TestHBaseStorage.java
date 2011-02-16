/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.pig.test;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.MiniZooKeeperCluster;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestHBaseStorage {

	private static final Log LOG = LogFactory.getLog(TestHBaseStorage.class);

	private static MiniCluster cluster = MiniCluster.buildCluster();
	private static HBaseConfiguration conf;
	private static MiniHBaseCluster hbaseCluster;
	private static MiniZooKeeperCluster zooKeeperCluster;

	private static PigServer pig;

	final static int NUM_REGIONSERVERS = 1;

	enum DataFormat {
		HBaseBinary, UTF8PlainText,
	}

	// Test Table constants
	private static final String TESTTABLE_1 = "pigtable_1";
	private static final String TESTTABLE_2 = "pigtable_2";
	private static final byte[] COLUMNFAMILY = Bytes.toBytes("pig");
	private static final String TESTCOLUMN_A = "pig:col_a";
	private static final String TESTCOLUMN_B = "pig:col_b";
	private static final String TESTCOLUMN_C = "pig:col_c";
	private static final HColumnDescriptor family = new HColumnDescriptor(
			COLUMNFAMILY);
	private static final int TEST_ROW_COUNT = 100;

	@BeforeClass
	public static void setUp() throws Exception {
		conf = new HBaseConfiguration();
		conf.set("fs.default.name", cluster.getFileSystem().getUri().toString());
		Path parentdir = cluster.getFileSystem().getHomeDirectory();
		conf.set(HConstants.HBASE_DIR, parentdir.toString());

		FSUtils.setVersion(cluster.getFileSystem(), parentdir);
		conf.set(HConstants.REGIONSERVER_PORT, "0");
		// disable UI or it clashes for more than one RegionServer
		conf.set("hbase.regionserver.info.port", "-1");
		
		// Make lease timeout longer, lease checks less frequent
		conf.setInt("hbase.master.lease.period", 10 * 1000);

		// Increase the amount of time between client retries
		conf.setLong("hbase.client.pause", 15 * 1000);

		try {
			hBaseClusterSetup();
		} catch (Exception e) {
			if (hbaseCluster != null) {
				hbaseCluster.shutdown();
			}
			throw e;
		}

		pig = new PigServer(ExecType.MAPREDUCE,
				ConfigurationUtil.toProperties(conf));
	}

	@AfterClass
	public static void oneTimeTearDown() throws Exception {
		try {
			HConnectionManager.deleteConnectionInfo(conf, true);
			if (hbaseCluster != null) {
				try {
					hbaseCluster.shutdown();
				} catch (Exception e) {
					LOG.warn("Closing mini hbase cluster", e);
				}
			}
			if (zooKeeperCluster != null) {
				try {
					zooKeeperCluster.shutdown();
				} catch (IOException e) {
					LOG.warn("Closing zookeeper cluster", e);
				}
			}
		} catch (Exception e) {
			LOG.error(e);
		}
		cluster.shutDown();
	}

	/**
	 * Actually start the MiniHBase instance.
	 */
	protected static void hBaseClusterSetup() throws Exception {
		zooKeeperCluster = new MiniZooKeeperCluster();
		int clientPort = zooKeeperCluster.startup(new File("build/test"));
		conf.set("hbase.zookeeper.property.clientPort", clientPort + "");
		// start the mini cluster
		hbaseCluster = new MiniHBaseCluster(conf, NUM_REGIONSERVERS);
		// opening the META table ensures that cluster is running
		while (true) {
			try {
				new HTable(conf, HConstants.META_TABLE_NAME);
				break;
			} catch (IOException e) {
				Thread.sleep(1000);
			}

		}
	}

	@After
	public void tearDown() throws Exception {
		// clear the table
		deleteTable(TESTTABLE_1);
		deleteTable(TESTTABLE_2);
		pig.shutdown();
	}

	/**
	 * load from hbase test
	 * 
	 * @throws IOException
	 */
	@Test
	public void testLoadFromHBase() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);
		pig.registerQuery("a = load 'hbase://" + TESTTABLE_1 + "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " " + TESTCOLUMN_C + " pig:col_d"
				+ "') as (col_a, col_b, col_c, col_d);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String col_a = ((DataByteArray) t.get(0)).toString();
			String col_b = ((DataByteArray) t.get(1)).toString();
			String col_c = ((DataByteArray) t.get(2)).toString();
			Object col_d = t.get(3);       // empty cell
			Assert.assertEquals(count, Integer.parseInt(col_a));
			Assert.assertEquals(count + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + count, col_c);
			Assert.assertNull(col_d);
			count++;
		}
		Assert.assertEquals(TEST_ROW_COUNT, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * load from hbase test without hbase:// prefix
	 * 
	 * @throws IOException
	 */
	@Test
	public void testBackwardsCompatibility() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);
		pig.registerQuery("a = load '" + TESTTABLE_1 + "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " " + TESTCOLUMN_C
				+ "') as (col_a, col_b, col_c);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String col_a = ((DataByteArray) t.get(0)).toString();
			String col_b = ((DataByteArray) t.get(1)).toString();
			String col_c = ((DataByteArray) t.get(2)).toString();

			Assert.assertEquals(count, Integer.parseInt(col_a));
			Assert.assertEquals(count + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + count, col_c);
			count++;
		}
		Assert.assertEquals(TEST_ROW_COUNT, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * load from hbase test including the row key as the first column
	 * 
	 * @throws IOException
	 */
	@Test
	public void testLoadFromHBaseWithRowKey() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);
		pig.registerQuery("a = load 'hbase://" + TESTTABLE_1 + "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " " + TESTCOLUMN_C
				+ "','-loadKey') as (rowKey,col_a, col_b, col_c);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String rowKey = ((DataByteArray) t.get(0)).toString();
			String col_a = ((DataByteArray) t.get(1)).toString();
			String col_b = ((DataByteArray) t.get(2)).toString();
			String col_c = ((DataByteArray) t.get(3)).toString();

			Assert.assertEquals("00".substring((count + "").length()) + count,
					rowKey);
			Assert.assertEquals(count, Integer.parseInt(col_a));
			Assert.assertEquals(count + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + count, col_c);

			count++;
		}
		Assert.assertEquals(TEST_ROW_COUNT, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * Test Load from hbase with parameters lte and gte (01<=key<=98)
	 * 
	 */
	@Test
	public void testLoadWithParameters_1() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);

		pig.registerQuery("a = load 'hbase://"
				+ TESTTABLE_1
				+ "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A
				+ " "
				+ TESTCOLUMN_B
				+ " "
				+ TESTCOLUMN_C
				+ "','-loadKey -gte 01 -lte 98') as (rowKey,col_a, col_b, col_c);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		int next = 1;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String rowKey = ((DataByteArray) t.get(0)).toString();
			String col_a = ((DataByteArray) t.get(1)).toString();
			String col_b = ((DataByteArray) t.get(2)).toString();
			String col_c = ((DataByteArray) t.get(3)).toString();

			Assert.assertEquals("00".substring((next + "").length()) + next,
					rowKey);
			Assert.assertEquals(next, Integer.parseInt(col_a));
			Assert.assertEquals(next + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + next, col_c);

			count++;
			next++;
		}
		Assert.assertEquals(TEST_ROW_COUNT - 2, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * Test Load from hbase with parameters lt and gt (00<key<99)
	 */
	@Test
	public void testLoadWithParameters_2() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);

		pig.registerQuery("a = load 'hbase://"
				+ TESTTABLE_1
				+ "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A
				+ " "
				+ TESTCOLUMN_B
				+ " "
				+ TESTCOLUMN_C
				+ "','-loadKey -gt 00 -lt 99') as (rowKey,col_a, col_b, col_c);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		int next = 1;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String rowKey = ((DataByteArray) t.get(0)).toString();
			String col_a = ((DataByteArray) t.get(1)).toString();
			String col_b = ((DataByteArray) t.get(2)).toString();
			String col_c = ((DataByteArray) t.get(3)).toString();

			Assert.assertEquals("00".substring((next + "").length()) + next,
					rowKey);
			Assert.assertEquals(next, Integer.parseInt(col_a));
			Assert.assertEquals(next + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + next, col_c);

			count++;
			next++;
		}
		Assert.assertEquals(TEST_ROW_COUNT - 2, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * Test Load from hbase with parameters limit
	 */
	@Test
	public void testLoadWithParameters_3() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.UTF8PlainText);
		pig.registerQuery("a = load 'hbase://" + TESTTABLE_1 + "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " " + TESTCOLUMN_C
				+ "','-loadKey -limit 10') as (rowKey,col_a, col_b, col_c);");
		Iterator<Tuple> it = pig.openIterator("a");
		int count = 0;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String rowKey = ((DataByteArray) t.get(0)).toString();
			String col_a = ((DataByteArray) t.get(1)).toString();
			String col_b = ((DataByteArray) t.get(2)).toString();
			String col_c = ((DataByteArray) t.get(3)).toString();

			Assert.assertEquals("00".substring((count + "").length()) + count,
					rowKey);
			Assert.assertEquals(count, Integer.parseInt(col_a));
			Assert.assertEquals(count + 0.0, Double.parseDouble(col_b), 1e-6);
			Assert.assertEquals("Text_" + count, col_c);

			count++;
		}
		// 'limit' apply for each region and here we have only one region
		Assert.assertEquals(10, count);
		LOG.info("LoadFromHBase done");
	}

	/**
	 * Test Load from hbase using HBaseBinaryConverter
	 */
	@Test
	public void testHBaseBinaryConverter() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.HBaseBinary);

		pig.registerQuery("a = load 'hbase://"
				+ TESTTABLE_1
				+ "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A
				+ " "
				+ TESTCOLUMN_B
				+ " "
				+ TESTCOLUMN_C
				+ "','-loadKey -caster HBaseBinaryConverter') as (rowKey:chararray,col_a:int, col_b:double, col_c:chararray);");
		Iterator<Tuple> it = pig.openIterator("a");
		int index = 0;
		LOG.info("LoadFromHBase Starting");
		while (it.hasNext()) {
			Tuple t = it.next();
			LOG.info("LoadFromHBase " + t);
			String rowKey = (String) t.get(0);
			int col_a = (Integer) t.get(1);
			double col_b = (Double) t.get(2);
			String col_c = (String) t.get(3);

			Assert.assertEquals("00".substring((index + "").length()) + index,
					rowKey);
			Assert.assertEquals(index, col_a);
			Assert.assertEquals(index + 0.0, col_b, 1e-6);
			Assert.assertEquals("Text_" + index, col_c);
			index++;
		}
		LOG.info("LoadFromHBase done");
	}

	/**
	 * load from hbase 'TESTTABLE_1' using HBaseBinary format, and store it into
	 * 'TESTTABLE_2' using HBaseBinaryFormat
	 * 
	 * @throws IOException
	 */
	@Test
	public void testStoreToHBase_1() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.HBaseBinary);
		prepareTable(TESTTABLE_2, false, DataFormat.HBaseBinary);

		pig.registerQuery("a = load 'hbase://"
				+ TESTTABLE_1
				+ "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A
				+ " "
				+ TESTCOLUMN_B
				+ " "
				+ TESTCOLUMN_C
				+ "','-loadKey -caster HBaseBinaryConverter') as (rowKey:chararray,col_a:int, col_b:double, col_c:chararray);");
		pig.store("a", TESTTABLE_2,
				"org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
						+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " "
						+ TESTCOLUMN_C + "','-caster HBaseBinaryConverter')");

		HTable table = new HTable(conf, TESTTABLE_2);
		ResultScanner scanner = table.getScanner(new Scan());
		Iterator<Result> iter = scanner.iterator();
		int i = 0;
		for (i = 0; iter.hasNext(); ++i) {
			Result result = iter.next();
			String v = i + "";
			String rowKey = Bytes.toString(result.getRow());
			int col_a = Bytes
					.toInt(result.getValue(Bytes.toBytes(TESTCOLUMN_A)));
			double col_b = Bytes.toDouble(result.getValue(Bytes
					.toBytes(TESTCOLUMN_B)));
			String col_c = Bytes.toString(result.getValue(Bytes
					.toBytes(TESTCOLUMN_C)));

			Assert.assertEquals("00".substring(v.length()) + v, rowKey);
			Assert.assertEquals(i, col_a);
			Assert.assertEquals(i + 0.0, col_b, 1e-6);
			Assert.assertEquals("Text_" + i, col_c);
		}
		Assert.assertEquals(100, i);
	}

	/**
	 * load from hbase 'TESTTABLE_1' using HBaseBinary format, and store it into
	 * 'TESTTABLE_2' using UTF-8 Plain Text format
	 * 
	 * @throws IOException
	 */
	@Test
	public void testStoreToHBase_2() throws IOException {
		prepareTable(TESTTABLE_1, true, DataFormat.HBaseBinary);
		prepareTable(TESTTABLE_2, false, DataFormat.HBaseBinary);

		pig.registerQuery("a = load 'hbase://"
				+ TESTTABLE_1
				+ "' using "
				+ "org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
				+ TESTCOLUMN_A
				+ " "
				+ TESTCOLUMN_B
				+ " "
				+ TESTCOLUMN_C
				+ "','-loadKey -caster HBaseBinaryConverter') as (rowKey:chararray,col_a:int, col_b:double, col_c:chararray);");
		pig.store("a", TESTTABLE_2,
				"org.apache.pig.backend.hadoop.hbase.HBaseStorage('"
						+ TESTCOLUMN_A + " " + TESTCOLUMN_B + " "
						+ TESTCOLUMN_C + "')");

		HTable table = new HTable(conf, TESTTABLE_2);
		ResultScanner scanner = table.getScanner(new Scan());
		Iterator<Result> iter = scanner.iterator();
		int i = 0;
		for (i = 0; iter.hasNext(); ++i) {
			Result result = iter.next();
			String v = i + "";
			String rowKey = new String(result.getRow());
			int col_a = Integer.parseInt(new String(result.getValue(Bytes
					.toBytes(TESTCOLUMN_A))));
			double col_b = Double.parseDouble(new String(result.getValue(Bytes
					.toBytes(TESTCOLUMN_B))));
			String col_c = new String(result.getValue(Bytes
					.toBytes(TESTCOLUMN_C)));

			Assert.assertEquals("00".substring(v.length()) + v, rowKey);
			Assert.assertEquals(i, col_a);
			Assert.assertEquals(i + 0.0, col_b, 1e-6);
			Assert.assertEquals("Text_" + i, col_c);
		}
		Assert.assertEquals(100, i);
	}

	/**
	 * Prepare a table in hbase for testing.
	 * 
	 */
	private void prepareTable(String tableName, boolean initData,
			DataFormat format) throws IOException {
		// define the table schema
		HTableDescriptor tabledesc = new HTableDescriptor(tableName);
		tabledesc.addFamily(family);

		// create the table
		HBaseAdmin admin = new HBaseAdmin(conf);
		deleteTable(tableName);
		admin.createTable(tabledesc);

		if (initData) {
			// put some data into table in the increasing order of row key
			HTable table = new HTable(conf, tableName);

			for (int i = 0; i < TEST_ROW_COUNT; i++) {
				String v = i + "";
				if (format == DataFormat.HBaseBinary) {
					// row key: string type
					Put put = new Put(Bytes.toBytes("00".substring(v.length())
							+ v));
					// col_a: int type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_a"),
							Bytes.toBytes(i));
					// col_b: double type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_b"),
							Bytes.toBytes(i + 0.0));
					// col_c: string type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_c"),
							Bytes.toBytes("Text_" + i));
					table.put(put);
				} else {
					// row key: string type
					Put put = new Put(
							("00".substring(v.length()) + v).getBytes());
					// col_a: int type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_a"),
							(i + "").getBytes()); // int
					// col_b: double type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_b"),
							((i + 0.0) + "").getBytes());
					// col_c: string type
					put.add(COLUMNFAMILY, Bytes.toBytes("col_c"),
							("Text_" + i).getBytes());
					table.put(put);
				}
			}
			table.flushCommits();
		}
	}

	/**
	 * delete the table after testing
	 * 
	 * @param tableName
	 * @throws IOException
	 */
	private void deleteTable(String tableName) throws IOException {
		// delete the table
		HBaseAdmin admin = new HBaseAdmin(conf);
		if (admin.tableExists(tableName)) {
			admin.disableTable(tableName);
			while (admin.isTableEnabled(tableName)) {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					// do nothing.
				}
			}
			admin.deleteTable(tableName);
		}
	}

}
