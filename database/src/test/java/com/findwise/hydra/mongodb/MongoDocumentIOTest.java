package com.findwise.hydra.mongodb;

import static org.junit.Assert.fail;

import java.util.Random;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import com.findwise.hydra.DatabaseDocument;
import com.findwise.hydra.DocumentWriter;
import com.findwise.hydra.TailableIterator;
import com.findwise.hydra.TestModule;
import com.findwise.hydra.common.Document.Status;
import com.google.inject.Guice;
import com.mongodb.DB;
import com.mongodb.DBCollection;

public class MongoDocumentIOTest {
	private MongoConnector mdc;

	private void createAndConnect() throws Exception {
	
		mdc = Guice.createInjector(new TestModule("junit-MongoDocumentIO")).getInstance(MongoConnector.class);
		
		mdc.waitForWrites(true);
		mdc.connect();
	}
	
	@Before
	public void setUp() throws Exception {
		createAndConnect();
		mdc.getDB().getCollection(MongoDocumentIO.OLD_DOCUMENT_COLLECTION).drop();
	}
	
	@AfterClass
	public static void tearDown() throws Exception {
		MongoDocumentIOTest test = new MongoDocumentIOTest();
		test.createAndConnect();
		test.mdc.getDB().dropDatabase();
	}
	
	@Test
	public void testPrepare() {
		DB db = mdc.getDB();
		
		if(db.getCollectionNames().contains(MongoDocumentIO.OLD_DOCUMENT_COLLECTION)) {
			fail("Collection already exists");
		}
		mdc.getDocumentWriter().prepare();
		
		if(!db.getCollectionNames().contains(MongoDocumentIO.OLD_DOCUMENT_COLLECTION)) {
			fail("Collection was not created");
		}
		
		if(!isCapped()) {
			fail("Collection not capped");
		}
	}
	
	private boolean isCapped() {
		DBCollection dbc = mdc.getDB().getCollection(MongoDocumentIO.OLD_DOCUMENT_COLLECTION);
		if(dbc.getStats().containsField("capped")) {
			return dbc.getStats().get("capped").equals(1);
		}
		return false;
	}
	
	@Test
	public void testConnectPrepare() throws Exception {
		mdc.getDB().dropDatabase();
		mdc.connect();
		if(!isCapped()) {
			fail("Collection was not capped on connect");
		}
	}
	
	@Test
	public void testRollover() throws Exception {
		DocumentWriter<MongoType> dw = mdc.getDocumentWriter();
		dw.prepare();
		for(int i=0; i<MongoPipelineStatus.DEFAULT_NUMBER_TO_KEEP; i++) {
			dw.insert(new MongoDocument());
			DatabaseDocument<MongoType> dd = dw.getAndTag(new MongoQuery(), "tag");
			dw.markProcessed(dd, "tag");
		}
		
		if(mdc.getDocumentReader().getActiveDatabaseSize()!=0) {
			fail("Still some active docs..");
		}
		if(mdc.getDocumentReader().getInactiveDatabaseSize()!=MongoPipelineStatus.DEFAULT_NUMBER_TO_KEEP) {
			fail("Incorrect number of old documents kept");
		}
		
		dw.insert(new MongoDocument());
		DatabaseDocument<MongoType> dd = dw.getAndTag(new MongoQuery(), "tag");
		dw.markProcessed(dd, "tag");
		
		if(mdc.getDocumentReader().getActiveDatabaseSize()!=0) {
			fail("Still some active docs..");
		}
		if(mdc.getDocumentReader().getInactiveDatabaseSize()!=MongoPipelineStatus.DEFAULT_NUMBER_TO_KEEP) {
			fail("Incorrect number of old documents kept: "+ mdc.getDocumentReader().getInactiveDatabaseSize());
		}
	}
	
	@Test
	public void testInactiveIterator() throws Exception {
		DocumentWriter<MongoType> dw = mdc.getDocumentWriter();
		dw.prepare();
		
		TailableIterator<MongoType> it = mdc.getDocumentReader().getInactiveIterator();
		
		TailReader tr = new TailReader(it);
		tr.start();
		
		MongoDocument first = new MongoDocument();
		first.putContentField("num", 1);
		dw.insert(first);
		DatabaseDocument<MongoType> dd = dw.getAndTag(new MongoQuery(), "tag");
		dw.markProcessed(dd, "tag");
		
		while(tr.lastRead>System.currentTimeMillis() && tr.isAlive()) {
			Thread.sleep(50);
		}
		
		if(!tr.isAlive()) {
			fail("TailableReader died");
		}
		
		long lastRead = tr.lastRead;
		
		if(!tr.lastReadDoc.getContentField("num").equals(1)) {
			fail("Last doc read was not the correct document!");
		}
		
		MongoDocument second = new MongoDocument();
		second.putContentField("num", 2);
		dw.insert(second);
		dd = dw.getAndTag(new MongoQuery(), "tag");
		dw.markProcessed(dd, "tag");
		
		while(tr.lastRead==lastRead) {
			Thread.sleep(50);
		}

		if (!tr.lastReadDoc.getContentField("num").equals(2)) {
			fail("Last doc read was not the correct document!");
		}

		
		if(tr.hasError) {
			fail("An exception was thrown by the TailableIterator prior to interrupt");
		}
		
		tr.interrupt();

		long interrupt = System.currentTimeMillis();
		
		while (tr.isAlive() && (System.currentTimeMillis()-interrupt)<10000) {
			Thread.sleep(50);
		}
		
		if(tr.isAlive()) {
			fail("Unable to interrupt the tailableiterator");
		}
		
		if(tr.hasError) {
			fail("An exception was thrown by the TailableIterator after interrupt");
		}
	}
	

	int testReadCount = 1000;
	@Test
	public void testReadStatus() throws Exception {
		mdc.getDocumentWriter().prepare();
		

		TailReader tr = new TailReader(mdc.getDocumentReader().getInactiveIterator());
		tr.start();
		
		Thread t = new Thread() {
			public void run() {
				try {
					insertDocuments(testReadCount);
					processDocuments(testReadCount/3);
					failDocuments(testReadCount/3);
					discardDocuments(testReadCount - (testReadCount/3)*2);
				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
			}
		};
		t.start();
		
		long timer = System.currentTimeMillis();
		
		while (tr.count < testReadCount && (System.currentTimeMillis()-timer)<10000) {
			Thread.sleep(50);
		}
		
		if(tr.count < testReadCount) {
			fail("Did not see all documents");
		}
		
		if(tr.count > testReadCount) {
			fail("Saw too many documents");
		}
		
		if(tr.countProcessed != testReadCount/3) {
			fail("Incorrect number of processed documents. Expected "+testReadCount/3+" but saw "+tr.countProcessed);
		}
		
		if(tr.countFailed != testReadCount/3) {
			fail("Incorrect number of failed documents. Expected "+testReadCount/3+" but saw "+tr.countFailed);
		}
		
		if(tr.countDiscarded != testReadCount - (testReadCount/3)*2) {
			fail("Incorrect number of discarded documents. Expected "+(testReadCount - (testReadCount/3)*2)+" but saw "+tr.countDiscarded);
		}
		
		tr.interrupt();
	}
	
	public long processDocuments(int count) throws Exception {
		long start = System.currentTimeMillis();
		DatabaseDocument<MongoType> dd;
		for(int i=0; i<count; i++) {
			dd = mdc.getDocumentReader().getDocument(new MongoQuery());
			mdc.getDocumentWriter().markProcessed(dd, "x");
		}
		return System.currentTimeMillis()-start;
	}
	
	public long failDocuments(int count) throws Exception {
		long start = System.currentTimeMillis();
		DatabaseDocument<MongoType> dd;
		for(int i=0; i<count; i++) {
			dd = mdc.getDocumentReader().getDocument(new MongoQuery());
			mdc.getDocumentWriter().markFailed(dd, "x");
		}
		return System.currentTimeMillis()-start;
	}
	
	public long discardDocuments(int count) throws Exception {
		long start = System.currentTimeMillis();
		DatabaseDocument<MongoType> dd;
		for(int i=0; i<count; i++) {
			dd = mdc.getDocumentReader().getDocument(new MongoQuery());
			mdc.getDocumentWriter().markDiscarded(dd, "x");
		}
		return System.currentTimeMillis()-start;
	}
	
	public long insertDocuments(int count) throws Exception {
		int error=0, success=0;
		long start = System.currentTimeMillis();
		for(int i=0; i<count; i++) {
			MongoDocument d = new MongoDocument();
			d.putContentField(getRandomString(5), getRandomString(20));
			if(mdc.getDocumentWriter().insert(d)) {
				success++;
			} else {
				error++;
			}
		}
		System.out.println("Success:" +success+" and Error:"+error);
		return System.currentTimeMillis()-start;
	}

	
	private String getRandomString(int length) {
		char[] ca = new char[length];

		Random r = new Random(System.currentTimeMillis());

		for (int i = 0; i < length; i++) {
			ca[i] = (char) ('A' + r.nextInt(26));
		}

		return new String(ca);
	}
	

	public static class TailReader extends Thread {
		private TailableIterator<MongoType> it;
		public long lastRead = Long.MAX_VALUE;
		public DatabaseDocument<MongoType> lastReadDoc = null;
		boolean hasError = false;
		
		int countFailed = 0;
		int countProcessed = 0;
		int countDiscarded = 0;
		
		int count = 0;
		
		public TailReader(TailableIterator<MongoType> it) {
			this.it = it;
		}

		public void run() {
			try {
				while (it.hasNext()) {
					lastRead = System.currentTimeMillis();
					lastReadDoc = it.next();
					
					Status s = lastReadDoc.getStatus();
					
					if(s==Status.DISCARDED) {
						countDiscarded++;
					} else if (s == Status.PROCESSED) {
						countProcessed++;
					} else if (s == Status.FAILED) {
						countFailed++;
					}
					
					count++;
				}
			} catch (Exception e) {
				e.printStackTrace();
				hasError = true;
			}
		}

		public void interrupt() {
			it.interrupt();
		}
	}
}
