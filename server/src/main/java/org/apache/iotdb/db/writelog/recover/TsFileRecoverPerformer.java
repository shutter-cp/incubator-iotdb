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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.writelog.recover;

import static org.apache.iotdb.db.engine.storagegroup.TsFileResource.RESOURCE_SUFFIX;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.engine.fileSystem.SystemFileFactory;
import org.apache.iotdb.db.engine.flush.MemTableFlushTask;
import org.apache.iotdb.db.engine.memtable.IMemTable;
import org.apache.iotdb.db.engine.memtable.PrimitiveMemTable;
import org.apache.iotdb.db.engine.modification.Deletion;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.engine.version.VersionController;
import org.apache.iotdb.db.exception.StorageGroupProcessorException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.utils.FileLoaderUtils;
import org.apache.iotdb.db.writelog.manager.MultiFileLogNodeManager;
import org.apache.iotdb.tsfile.exception.NotCompatibleTsFileException;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.write.writer.RestorableTsFileIOWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TsFileRecoverPerformer recovers a SeqTsFile to correct status, redoes the WALs since last crash
 * and removes the redone logs.
 */
public class TsFileRecoverPerformer {

  private static final Logger logger = LoggerFactory.getLogger(TsFileRecoverPerformer.class);

  private String filePath;
  private String logNodePrefix;
  private VersionController versionController;
  private TsFileResource resource;
  private boolean acceptUnseq;
  private boolean isLastFile;

  public TsFileRecoverPerformer(String logNodePrefix, VersionController versionController,
      TsFileResource currentTsFileResource, boolean acceptUnseq, boolean isLastFile) {
    this.filePath = currentTsFileResource.getPath();
    this.logNodePrefix = logNodePrefix;
    this.versionController = versionController;
    this.resource = currentTsFileResource;
    this.acceptUnseq = acceptUnseq;
    this.isLastFile = isLastFile;
  }

  /**
   * 1. recover the TsFile by RestorableTsFileIOWriter and truncate the file to remaining corrected
   * data 2. redo the WALs to recover unpersisted data 3. flush and close the file 4. clean WALs
   *
   * @return a RestorableTsFileIOWriter if the file is not closed before crash, so this writer can
   * be used to continue writing
   */
  public RestorableTsFileIOWriter recover() throws StorageGroupProcessorException {

    File file = FSFactoryProducer.getFSFactory().getFile(filePath);
    if (!file.exists()) {
      logger.error("TsFile {} is missing, will skip its recovery.", filePath);
      return null;
    }
    // remove corrupted part of the TsFile
    RestorableTsFileIOWriter restorableTsFileIOWriter;
    try {
      restorableTsFileIOWriter = new RestorableTsFileIOWriter(file);
    } catch (NotCompatibleTsFileException e) {
      boolean result = file.delete();
      logger.warn("TsFile {} is incompatible. Delete it successfully {}", filePath, result);
      throw new StorageGroupProcessorException(e);
    } catch (IOException e) {
      throw new StorageGroupProcessorException(e);
    }

    if (!restorableTsFileIOWriter.hasCrashed() && !restorableTsFileIOWriter.canWrite()) {
      // tsfile is complete
      try {
        if (resource.fileExists()) {
          // .resource file exists, deserialize it
          recoverResourceFromFile();
        } else {
          // .resource file does not exist, read file metadata and recover tsfile resource
          try (TsFileSequenceReader reader = new TsFileSequenceReader(
              resource.getFile().getAbsolutePath())) {
            FileLoaderUtils.updateTsFileResource(reader, resource);
          }
          // write .resource file
          long fileVersion =
              Long.parseLong(
                  resource.getFile().getName().split(IoTDBConstant.TSFILE_NAME_SEPARATOR)[1]);
          resource.setHistoricalVersions(Collections.singleton(fileVersion));
          resource.serialize();
        }
        return restorableTsFileIOWriter;
      } catch (IOException e) {
        throw new StorageGroupProcessorException(
            "recover the resource file failed: " + filePath
                + RESOURCE_SUFFIX + e);
      }
    } else {
      // due to failure, the last ChunkGroup may contain the same data as the WALs, so the time
      // map must be updated first to avoid duplicated insertion
      recoverResourceFromWriter(restorableTsFileIOWriter, resource.getModFile());

      // redo logs
      redoLogs(restorableTsFileIOWriter);

      // clean logs
      try {
        MultiFileLogNodeManager.getInstance()
            .deleteNode(logNodePrefix + SystemFileFactory.INSTANCE.getFile(filePath).getName());
      } catch (IOException e) {
        throw new StorageGroupProcessorException(e);
      }

      return restorableTsFileIOWriter;
    }
  }

  private void recoverResourceFromFile() throws IOException {
    try {
      resource.deserialize();
    } catch (IOException e) {
      logger.warn("Cannot deserialize TsFileResource {}, construct it using "
          + "TsFileSequenceReader", resource.getFile(), e);
      recoverResourceFromReader();
    }
  }


  private void recoverResourceFromReader() throws IOException {
    try (TsFileSequenceReader reader =
        new TsFileSequenceReader(resource.getFile().getAbsolutePath(), true)) {
      for (Entry<String, List<TimeseriesMetadata>> entry : reader.getAllTimeseriesMetadata()
          .entrySet()) {
        for (TimeseriesMetadata timeseriesMetaData : entry.getValue()) {
          resource
              .updateStartTime(entry.getKey(), timeseriesMetaData.getStatistics().getStartTime());
          resource.updateEndTime(entry.getKey(), timeseriesMetaData.getStatistics().getEndTime());
        }
      }
    }
    // write .resource file
    resource.serialize();
  }


  private void recoverResourceFromWriter(RestorableTsFileIOWriter restorableTsFileIOWriter,
      ModificationFile modFile) {
    for (Modification modification : modFile.getModifications()) {
      if (((Deletion) modification).getTimestamp() != Long.MAX_VALUE) {
        continue;
      }
      String deviceId = modification.getDevice();
      String measurementId = modification.getMeasurement();
      TSDataType type = null;
      try {
        type = MManager.getInstance().getSeriesSchema(deviceId, measurementId).getType();
      } catch (MetadataException e) {
        logger.error("Meet error when recovering resource from writer. ", e);
      }
      restorableTsFileIOWriter
          .deleteMeasurementFromChunkGroupMetadataList(deviceId, measurementId, type);
    }

    Map<String, List<ChunkMetadata>> deviceChunkMetaDataMap =
        restorableTsFileIOWriter.getDeviceChunkMetadataMap();
    for (Map.Entry<String, List<ChunkMetadata>> entry : deviceChunkMetaDataMap.entrySet()) {
      String deviceId = entry.getKey();
      List<ChunkMetadata> chunkMetadataList = entry.getValue();
      for (ChunkMetadata chunkMetaData : chunkMetadataList) {
        resource.updateStartTime(deviceId, chunkMetaData.getStartTime());
        resource.updateEndTime(deviceId, chunkMetaData.getEndTime());
      }
    }
    long fileVersion =
        Long.parseLong(resource.getFile().getName().split(IoTDBConstant.TSFILE_NAME_SEPARATOR)[1]);
    resource.setHistoricalVersions(Collections.singleton(fileVersion));
  }

  private void redoLogs(RestorableTsFileIOWriter restorableTsFileIOWriter)
      throws StorageGroupProcessorException {
    IMemTable recoverMemTable = new PrimitiveMemTable();
    recoverMemTable.setVersion(versionController.nextVersion());
    LogReplayer logReplayer = new LogReplayer(logNodePrefix, filePath, resource.getModFile(),
        versionController, resource, recoverMemTable, acceptUnseq);
    logReplayer.replayLogs();
    try {
      if (!recoverMemTable.isEmpty()) {
        // flush logs
        MemTableFlushTask tableFlushTask = new MemTableFlushTask(recoverMemTable,
            restorableTsFileIOWriter, resource.getFile().getParentFile().getParentFile().getName());
        tableFlushTask.syncFlushMemTable();
      }

      if (!isLastFile || resource.isCloseFlagSet()) {
        // end the file if it is not the last file or it is closed before crush
        restorableTsFileIOWriter.endFile();
        resource.cleanCloseFlag();
        resource.serialize();
      }
      // otherwise this file is not closed before crush, do nothing so we can continue writing
      // into it
    } catch (IOException | InterruptedException | ExecutionException e) {
      throw new StorageGroupProcessorException(e);
    }
  }

}
