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
package org.apache.pig.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import junit.framework.Assert;

import org.apache.pig.ExecType;
import org.apache.pig.FuncSpec;
import org.apache.pig.PigServer;
import org.apache.pig.backend.datastorage.DataStorage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.builtin.PigStorage;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DefaultBagFactory;
import org.apache.pig.data.DefaultTuple;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileLocalizer;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.logicalLayer.LOLoad;
import org.apache.pig.impl.logicalLayer.LogicalOperator;
import org.apache.pig.impl.logicalLayer.LogicalPlan;
import org.apache.pig.impl.logicalLayer.LogicalPlanBuilder;
import org.apache.pig.impl.logicalLayer.parser.ParseException;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.test.utils.GenPhyOp;
import org.apache.pig.test.utils.LogicalPlanTester;
import org.apache.pig.test.utils.TestHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestLoad extends junit.framework.TestCase {

    PigContext pc;
    PigServer[] servers;
    
    static MiniCluster cluster = MiniCluster.buildCluster();
    
    @Override
    @Before
    public void setUp() throws Exception {
        FileLocalizer.deleteTempFiles();
        servers = new PigServer[] { 
                    new PigServer(ExecType.MAPREDUCE, cluster.getProperties()),
                    new PigServer(ExecType.LOCAL, new Properties())
        };       
    }
        
    @Override
    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testGetNextTuple() throws IOException {
        pc = servers[0].getPigContext();
        String curDir = System.getProperty("user.dir");
        String inpDir = curDir + File.separatorChar + "test/org/apache/pig/test/data/InputFiles/";
        if ((System.getProperty("os.name").toUpperCase().startsWith("WINDOWS")))
            inpDir="/"+FileLocalizer.parseCygPath(inpDir, FileLocalizer.STYLE_WINDOWS);
        // copy passwd file to cluster and set that as the input location for the load
        Util.copyFromLocalToCluster(cluster, inpDir + "passwd", "passwd");
        FileSpec inpFSpec = new FileSpec("passwd", new FuncSpec(PigStorage.class.getName(), new String[]{":"}));
        POLoad ld = GenPhyOp.topLoadOp();
        ld.setLFile(inpFSpec);
        ld.setPc(pc);
        
        DataBag inpDB = DefaultBagFactory.getInstance().newDefaultBag();
        BufferedReader br = new BufferedReader(new FileReader("test/org/apache/pig/test/data/InputFiles/passwd"));
        
        for(String line = br.readLine();line!=null;line=br.readLine()){
            String[] flds = line.split(":",-1);
            Tuple t = new DefaultTuple();
            for (String fld : flds) {
                t.append((fld.compareTo("")!=0 ? new DataByteArray(fld.getBytes()) : null));
            }
            inpDB.add(t);
        }
        Tuple t=null;
        int size = 0;
        for(Result res = ld.getNext(t);res.returnStatus!=POStatus.STATUS_EOP;res=ld.getNext(t)){
            assertEquals(true, TestHelper.bagContains(inpDB, (Tuple)res.result));
            ++size;
        }
        assertEquals(true, size==inpDB.size());
    }

    @AfterClass
    public static void oneTimeTearDown() throws Exception {
        cluster.shutDown();
    }
    
    @Test
    public void testLoadRemoteRel() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("test","/tmp/test");
        }
    }

    @Test
    public void testLoadRemoteAbs() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            boolean noConversionExpected = true;
            checkLoadPath("/tmp/test","/tmp/test", noConversionExpected);
        }
    }

    @Test
    public void testLoadRemoteRelScheme() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("test","/tmp/test");
        }
    }

    @Test
    public void testLoadRemoteAbsScheme() throws Exception {
        pc = servers[0].getPigContext();
        boolean noConversionExpected = true;
        checkLoadPath("hdfs:/tmp/test","hdfs:/tmp/test", noConversionExpected);
        
        // check if a location 'hdfs:<abs path>' can actually be read using PigStorage
        String[] inputFileNames = new String[] {
                "/tmp/TestLoad-testLoadRemoteAbsSchema-input.txt"};
        testLoadingMultipleFiles(inputFileNames, "hdfs:" + inputFileNames[0]);
    }

    @Test
    public void testLoadRemoteAbsAuth() throws Exception {
        pc = servers[0].getPigContext();
        checkLoadPath("hdfs://localhost:9000/test","/test");
    }

    @Test
    public void testLoadRemoteNormalize() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            boolean noConversionExpected = true;
            checkLoadPath("/tmp/foo/../././","/tmp/foo/.././.", noConversionExpected);
        }
    }

    @Test
    public void testGlobChars() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("t?s*","/tmp/t?s*");
        }
    }

    @Test
    public void testCommaSeparatedString() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("usr/pig/a,usr/pig/b","/tmp/usr/pig/a,/tmp/usr/pig/b");
        }
    }

    @Test
    public void testCommaSeparatedString2() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("t?s*,test","/tmp/t?s*,/tmp/test");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testCommaSeparatedString3() throws Exception {
        PigServer pig = servers[0];
        pc = pig.getPigContext();
        boolean noConversionExpected = true;
        checkLoadPath("hdfs:/tmp/test,hdfs:/tmp/test2,hdfs:/tmp/test3",
                "hdfs:/tmp/test,hdfs:/tmp/test2,hdfs:/tmp/test3", noConversionExpected );
        
        // check if a location 'hdfs:<abs path>,hdfs:<abs path>' can actually be 
        // read using PigStorage
        String[] inputFileNames = new String[] {
                "/tmp/TestLoad-testCommaSeparatedString3-input1.txt",
                "/tmp/TestLoad-testCommaSeparatedString3-input2.txt"};
        String inputString = "hdfs:" + inputFileNames[0] + ",hdfs:" + 
        inputFileNames[1];
        testLoadingMultipleFiles(inputFileNames, inputString);
        
    }
    
    @Test
    public void testCommaSeparatedString4() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("usr/pig/{a,c},usr/pig/b","/tmp/usr/pig/{a,c},/tmp/usr/pig/b");
        }
    }

    @Test
    public void testCommaSeparatedString5() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("/usr/pig/{a,c},usr/pig/b","/usr/pig/{a,c},/tmp/usr/pig/b");
        }
       
        // check if a location '<abs path>,<relative path>' can actually be 
        // read using PigStorage
        String loadLocationString = "/tmp/TestLoad-testCommaSeparatedStringMixed-input{1,2}.txt," +
        "TestLoad-testCommaSeparatedStringMixed-input3.txt"; // current working dir is set to /tmp in checkLoadPath()
       
        String[] inputFileNames = new String[] {
                "/tmp/TestLoad-testCommaSeparatedStringMixed-input1.txt",
                "/tmp/TestLoad-testCommaSeparatedStringMixed-input2.txt",
                "/tmp/TestLoad-testCommaSeparatedStringMixed-input3.txt",};
        pc = servers[0].getPigContext(); // test in map reduce mode
        testLoadingMultipleFiles(inputFileNames, loadLocationString);
    }
    
    @Test
    public void testCommaSeparatedString6() throws Exception {
        for (PigServer pig : servers) {
            pc = pig.getPigContext();
            checkLoadPath("usr/pig/{a,c},/usr/pig/b","/tmp/usr/pig/{a,c},/usr/pig/b");
        }
    }
    
    @Test
    public void testNonDfsLocation() throws Exception {
        LogicalPlanTester lpt = new LogicalPlanTester();
        String nonDfsUrl = "har://hdfs-namenode/user/foo/";
        LogicalPlan lp = lpt.buildPlan("a = load '" + nonDfsUrl + "';");
        LOLoad load = (LOLoad) lp.getRoots().get(0);
        nonDfsUrl = nonDfsUrl.replaceFirst("/$", "");
        Assert.assertEquals(nonDfsUrl, load.getInputFile().getFileName());
    }
    
    @SuppressWarnings("unchecked")
    private void testLoadingMultipleFiles(String[] inputFileNames, 
            String loadLocationString) throws IOException, ParseException {
        
        String[][] inputStrings = new String[][] {
                new String[] { "hello\tworld"},
                new String[] { "bye\tnow"},
                new String[] { "all\tgood"}
        };
        List<Tuple> expected = Arrays.asList(new Tuple[] {
                (Tuple) Util.getPigConstant("('hello', 'world')"),
                (Tuple) Util.getPigConstant("('bye', 'now')"),
                (Tuple) Util.getPigConstant("('all', 'good')")});
        
        List<Tuple> expectedBasedOnNumberOfInputs = new ArrayList<Tuple>();
        for(int i = 0; i < inputFileNames.length; i++) {
            Util.createInputFile(pc, inputFileNames[i], inputStrings[i]);
            expectedBasedOnNumberOfInputs.add(expected.get(i));
        }
        try {
            servers[0].registerQuery(" a = load '" + loadLocationString + "' as " +
                    "(s1:chararray, s2:chararray);");
            Iterator<Tuple> it = servers[0].openIterator("a");
            
            List<Tuple> actual = new ArrayList<Tuple>();
            while(it.hasNext()) {
                actual.add(it.next());
            }
            Collections.sort(expectedBasedOnNumberOfInputs);
            Collections.sort(actual);
            Assert.assertEquals(expectedBasedOnNumberOfInputs, actual);
        } finally {
            for(int i = 0; i < inputFileNames.length; i++) {
                Util.deleteFile(pc, inputFileNames[i]);
            }
        }
    }
    
    private void checkLoadPath(String orig, String expected) throws Exception {
        checkLoadPath(orig, expected, false);
    }

    private void checkLoadPath(String orig, String expected, 
            boolean noConversionExpected) throws Exception {
        
        boolean[] multiquery = {true, false};
        
        for (boolean b : multiquery) {
            pc.getProperties().setProperty("opt.multiquery", "" + b);
                    
            DataStorage dfs = pc.getDfs();
            dfs.setActiveContainer(dfs.asContainer("/tmp"));
            Map<LogicalOperator, LogicalPlan> aliases = new HashMap<LogicalOperator, LogicalPlan>();
            Map<OperatorKey, LogicalOperator> logicalOpTable = new HashMap<OperatorKey, LogicalOperator>();
            Map<String, LogicalOperator> aliasOp = new HashMap<String, LogicalOperator>();
            Map<String, String> fileNameMap = new HashMap<String, String>();
            
            LogicalPlanBuilder builder = new LogicalPlanBuilder(pc);
            
            String query = "a = load '"+orig+"';";
            LogicalPlan lp = builder.parse("Test-Load",
                                           query,
                                           aliases,
                                           logicalOpTable,
                                           aliasOp,
                                           fileNameMap);
            Assert.assertTrue(lp.size()>0);
            LogicalOperator op = lp.getRoots().get(0);
            
            Assert.assertTrue(op instanceof LOLoad);
            LOLoad load = (LOLoad)op;
    
            String p = load.getInputFile().getFileName();
            System.err.println("DEBUG: p:" + p + " expected:" + expected +", exectype:" + pc.getExecType());
            if(noConversionExpected) {
                Assert.assertEquals(p, expected);
            } else  {
                if (pc.getExecType() == ExecType.MAPREDUCE) {
                    Assert.assertTrue(p.matches(".*hdfs://[0-9a-zA-Z:\\.]*.*"));
                    Assert.assertEquals(p.replaceAll("hdfs://[0-9a-zA-Z:\\.]*/", "/"),
                            expected);
                } else {
                    Assert.assertTrue(p.matches(".*file://[0-9a-zA-Z:\\.]*.*"));
                    Assert.assertEquals(p.replaceAll("file://[0-9a-zA-Z:\\.]*/", "/"),
                            expected);
                }
            }
        }
    }
}
