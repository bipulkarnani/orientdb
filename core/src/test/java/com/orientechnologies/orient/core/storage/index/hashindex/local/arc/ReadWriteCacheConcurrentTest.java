package com.orientechnologies.orient.core.storage.index.hashindex.local.arc;

import com.orientechnologies.common.collection.closabledictionary.OClosableLinkedContainer;
import com.orientechnologies.common.directmemory.OByteBufferPool;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.storage.OChecksumMode;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cache.OCachePointer;
import com.orientechnologies.orient.core.storage.cache.local.OWOWCache;
import com.orientechnologies.orient.core.storage.cache.local.twoq.O2QCache;
import com.orientechnologies.orient.core.storage.fs.OFileClassic;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * @author Artem Loginov
 */
public class ReadWriteCacheConcurrentTest {
  private static final int                                  THREAD_COUNT    = 4;
  private static final int                                  PAGE_COUNT      = 20;
  private static final int                                  FILE_COUNT      = 8;
  private final        int                                  systemOffset    =
      2 * (OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
  private final        ExecutorService                      executorService = Executors.newFixedThreadPool(THREAD_COUNT);
  private final        List<Future<Void>>                   futures         = new ArrayList<Future<Void>>(THREAD_COUNT);
  private final        AtomicReferenceArray<Queue<Integer>> pagesQueue      = new AtomicReferenceArray<Queue<Integer>>(FILE_COUNT);
  private O2QCache               readBuffer;
  private OWOWCache              writeBuffer;
  private OLocalPaginatedStorage storageLocal;
  private String[]               fileNames;
  private byte                   seed;
  private AtomicLongArray                              fileIds         = new AtomicLongArray(FILE_COUNT);
  private AtomicIntegerArray                           pageCounters    = new AtomicIntegerArray(FILE_COUNT);
  private AtomicBoolean                                continuousWrite = new AtomicBoolean(true);
  private AtomicInteger                                version         = new AtomicInteger(1);
  private OClosableLinkedContainer<Long, OFileClassic> files           = new OClosableLinkedContainer<Long, OFileClassic>(1024);
  private OByteBufferPool                              bufferPool      = new OByteBufferPool(8 + systemOffset);
  ;

  @Before
  public void beforeClass() throws IOException {

    OGlobalConfiguration.FILE_LOCK.setValue(Boolean.FALSE);

    String buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null)
      buildDirectory = ".";

    storageLocal = (OLocalPaginatedStorage) Orient.instance().getEngine("plocal")
        .createStorage(buildDirectory + "/ReadWriteCacheConcurrentTest", null);
    //loadStorage("plocal:" + buildDirectory + "/ReadWriteCacheConcurrentTest");
    storageLocal.create(null);

    prepareFilesForTest(FILE_COUNT);

  }

  private void prepareFilesForTest(int filesCount) {
    fileNames = new String[filesCount];
    for (int i = 0; i < fileNames.length; i++) {
      fileNames[i] = "readWriteCacheTest" + i + ".tst";
    }
  }

  @Before
  public void beforeMethod() throws Exception {
    if (writeBuffer != null && readBuffer != null)
      readBuffer.closeStorage(writeBuffer);
    else if (writeBuffer != null)
      writeBuffer.close();

    if (readBuffer != null) {
      readBuffer.clear();

      deleteUsedFiles(FILE_COUNT);
    }

    initBuffer();

    Random random = new Random();
    seed = (byte) (random.nextInt() & 0xFF);
  }

  private void initBuffer() throws IOException, InterruptedException {
    writeBuffer = new OWOWCache(8 + systemOffset, bufferPool, null, -1, 15000 * (8 + systemOffset), storageLocal, true, files, 1,
        OChecksumMode.StoreAndThrow);
    writeBuffer.loadRegisteredFiles();
    readBuffer = new O2QCache(4 * (8 + systemOffset), 8 + systemOffset, true, 20);
  }

  @After
  public void afterClass() throws IOException {
    readBuffer.closeStorage(writeBuffer);
    readBuffer.clear();

    deleteUsedFiles(FILE_COUNT);

    storageLocal.delete();
    bufferPool.clear();
  }

  private void deleteUsedFiles(int filesCount) throws IOException {
    for (int k = 0; k < filesCount; k++) {
      final long fileId = writeBuffer.fileIdByName("readWriteCacheTest" + k + ".tst");
      final String nativeFileName = writeBuffer.nativeFileNameById(fileId);

      readBuffer.deleteFile(fileId, writeBuffer);

      File file = new File(storageLocal.getConfiguration().getDirectory() + "/" + nativeFileName);
      Assert.assertFalse(file.exists());
    }
  }

  public void testAdd() throws Exception {
    getIdentitiesOfFiles();

    fillFilesWithContent();

    validateFilesContent(version.byteValue());

    version.compareAndSet(1, 2);
    continuousWrite.compareAndSet(true, false);

    generateRemainingPagesQueueForAllFiles();

    executeConcurrentRandomReadAndWriteOperations();

    writeBuffer.flush();

    validateFilesContent(version.byteValue());
  }

  private void executeConcurrentRandomReadAndWriteOperations() throws InterruptedException, ExecutionException {
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Writer()));
    }
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Reader()));
    }

    for (Future<Void> future : futures)
      future.get();
  }

  private void generateRemainingPagesQueueForAllFiles() {
    List<Integer>[] array = new ArrayList[FILE_COUNT];
    for (int k = 0; k < FILE_COUNT; ++k) {
      array[k] = new ArrayList<Integer>(PAGE_COUNT);
      for (Integer i = 0; i < PAGE_COUNT; ++i) {
        array[k].add(i);
      }
    }

    for (int i = 0; i < FILE_COUNT; ++i) {
      Collections.shuffle(array[i]);
      pagesQueue.set(i, new ConcurrentLinkedQueue<Integer>(array[i]));
    }
  }

  private void fillFilesWithContent() throws InterruptedException, ExecutionException, IOException {
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executorService.submit(new Writer()));
    }

    for (Future<Void> future : futures)
      future.get();

    futures.clear();

    writeBuffer.flush();
  }

  private void getIdentitiesOfFiles() throws IOException {
    for (int i = 0; i < fileIds.length(); i++) {
      fileIds.set(i, readBuffer.addFile(fileNames[i], writeBuffer));
    }
  }

  private void validateFilesContent(byte version) throws IOException {
    for (int k = 0; k < FILE_COUNT; ++k) {
      validateFileContent(version, k);
    }
  }

  private void validateFileContent(byte version, int k) throws IOException {
    final long fileId = writeBuffer.fileIdByName("readWriteCacheTest" + k + ".tst");
    final String nativeFileName = writeBuffer.nativeFileNameById(fileId);
    String path = storageLocal.getConfiguration().getDirectory() + "/" + nativeFileName;

    OFileClassic fileClassic = new OFileClassic(Paths.get(path));
    fileClassic.open();

    for (int i = 0; i < PAGE_COUNT; i++) {
      byte[] content = new byte[8];
      fileClassic.read(i * (8 + systemOffset) + systemOffset, content, 8);

      Assert.assertEquals(content, new byte[] { version, 2, 3, seed, 5, 6, (byte) k, (byte) (i & 0xFF) });
    }
    fileClassic.close();
  }

  private class Writer implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      int fileNumber = getNextFileNumber();
      while (shouldContinue(fileNumber)) {
        final long pageIndex = getNextPageIndex(fileNumber);
        if (pageIndex >= 0) {
          writeToFile(fileNumber, pageIndex);
        }
        fileNumber = getNextFileNumber();
      }
      return null;
    }

    private void writeToFile(int fileNumber, long pageIndex) throws IOException {
      OCacheEntry cacheEntry = readBuffer.loadForWrite(fileIds.get(fileNumber), pageIndex, false, writeBuffer, 1, true);
      if (cacheEntry == null) {
        do {
          if (cacheEntry != null)
            readBuffer.releaseFromWrite(cacheEntry, writeBuffer);

          cacheEntry = readBuffer.allocateNewPage(fileIds.get(fileNumber), writeBuffer, true);
        } while (cacheEntry.getPageIndex() < pageIndex);
      }

      if (cacheEntry.getPageIndex() > pageIndex) {
        readBuffer.releaseFromWrite(cacheEntry, writeBuffer);
        cacheEntry = readBuffer.loadForWrite(fileIds.get(fileNumber), pageIndex, false, writeBuffer, 1, true);
      }

      OCachePointer pointer = cacheEntry.getCachePointer();

      final ByteBuffer buffer = pointer.getBufferDuplicate();
      buffer.position(systemOffset);
      buffer.put(new byte[] { version.byteValue(), 2, 3, seed, 5, 6, (byte) fileNumber, (byte) (pageIndex & 0xFF) });

      readBuffer.releaseFromWrite(cacheEntry, writeBuffer);
    }

    private long getNextPageIndex(int fileNumber) {
      if (continuousWrite.get()) {
        return pageCounters.getAndIncrement(fileNumber);
      } else {
        final Integer pageIndex = pagesQueue.get(fileNumber).poll();

        if (pageIndex == null) {
          return -1;
        } else {
          return pageIndex;
        }
      }
    }

    private boolean shouldContinue(int fileNumber) {
      return fileNumber != -1;
    }

    public int getNextFileNumber() {
      int firstFileNumber = new Random().nextInt(FILE_COUNT - 1);
      for (int i = 0; i < FILE_COUNT; ++i) {
        int fileNumber = (firstFileNumber + i) % FILE_COUNT;
        if (isFileFull(fileNumber))
          return fileNumber;
      }
      return -1;
    }

    private boolean isFileFull(int fileNumber) {
      if (continuousWrite.get()) {
        return pageCounters.get(fileNumber) < PAGE_COUNT;
      } else {
        return !pagesQueue.get(fileNumber).isEmpty();
      }
    }
  }

  private class Reader implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      long pageIndex = Math.abs(new Random().nextInt() % PAGE_COUNT);
      int fileNumber = new Random().nextInt(FILE_COUNT);

      OCacheEntry cacheEntry = readBuffer.loadForRead(fileIds.get(fileNumber), pageIndex, false, writeBuffer, 1, true);
      OCachePointer pointer = cacheEntry.getCachePointer();

      final ByteBuffer buffer = pointer.getBufferDuplicate();
      buffer.position(systemOffset);
      byte[] content = new byte[8];
      buffer.get(content);

      readBuffer.releaseFromRead(cacheEntry, writeBuffer);

      Assert.assertTrue(content[0] == 1 || content[0] == 2);
      Assert.assertEquals(content, new byte[] { content[0], 2, 3, seed, 5, 6, (byte) fileNumber, (byte) (pageIndex & 0xFF) });
      return null;
    }
  }
}
