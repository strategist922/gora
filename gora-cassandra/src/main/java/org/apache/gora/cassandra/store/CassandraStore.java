/**
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

package org.apache.gora.cassandra.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.HSuperColumn;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.SuperRow;
import me.prettyprint.hector.api.beans.SuperSlice;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericArray;
import org.apache.avro.util.Utf8;
import org.apache.gora.cassandra.query.CassandraQuery;
import org.apache.gora.cassandra.query.CassandraResult;
import org.apache.gora.cassandra.query.CassandraResultSet;
import org.apache.gora.cassandra.query.CassandraRow;
import org.apache.gora.cassandra.query.CassandraSubColumn;
import org.apache.gora.cassandra.query.CassandraSuperColumn;
import org.apache.gora.persistency.Persistent;
import org.apache.gora.persistency.StatefulHashMap;
import org.apache.gora.persistency.impl.PersistentBase;
import org.apache.gora.persistency.impl.StateManagerImpl;
import org.apache.gora.query.PartitionQuery;
import org.apache.gora.query.Query;
import org.apache.gora.query.Result;
import org.apache.gora.query.impl.PartitionQueryImpl;
import org.apache.gora.store.impl.DataStoreBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraStore<K, T extends Persistent> extends DataStoreBase<K, T> {
  public static final Logger LOG = LoggerFactory.getLogger(CassandraStore.class);
  
  private CassandraClient<K, T>  cassandraClient = new CassandraClient<K, T>();

  /**
   * The values are Avro fields pending to be stored.
   *
   * We want to iterate over the keys in insertion order.
   * We don't want to lock the entire collection before iterating over the keys, since in the meantime other threads are adding entries to the map.
   */
  private Map<K, T> buffer = new LinkedHashMap<K, T>();
  
  public CassandraStore() throws Exception {
    this.cassandraClient.init();
  }

  @Override
  public void close() throws IOException {
    LOG.debug("close");
    flush();
  }

  @Override
  public void createSchema() {
    LOG.debug("create schema");
    this.cassandraClient.checkKeyspace();
  }

  @Override
  public boolean delete(K key) throws IOException {
    LOG.debug("delete " + key);
    return false;
  }

  @Override
  public long deleteByQuery(Query<K, T> query) throws IOException {
    LOG.debug("delete by query " + query);
    return 0;
  }

  @Override
  public void deleteSchema() throws IOException {
    LOG.debug("delete schema");
    this.cassandraClient.dropKeyspace();
  }

  @Override
  public Result<K, T> execute(Query<K, T> query) throws IOException {
    
    Map<String, List<String>> familyMap = this.cassandraClient.getFamilyMap(query);
    Map<String, String> reverseMap = this.cassandraClient.getReverseMap(query);
    
    CassandraQuery<K, T> cassandraQuery = new CassandraQuery<K, T>();
    cassandraQuery.setQuery(query);
    cassandraQuery.setFamilyMap(familyMap);
    
    CassandraResult<K, T> cassandraResult = new CassandraResult<K, T>(this, query);
    cassandraResult.setReverseMap(reverseMap);

    CassandraResultSet cassandraResultSet = new CassandraResultSet();
    
    // We query Cassandra keyspace by families.
    for (String family : familyMap.keySet()) {
      if (this.cassandraClient.isSuper(family)) {
        addSuperColumns(family, cassandraQuery, cassandraResultSet);
         
      } else {
        addSubColumns(family, cassandraQuery, cassandraResultSet);
      
      }
      
    }
    
    cassandraResult.setResultSet(cassandraResultSet);
    
    
    return cassandraResult;

  }

  private void addSubColumns(String family, CassandraQuery<K, T> cassandraQuery,
      CassandraResultSet cassandraResultSet) {
    // select family columns that are included in the query
    List<Row<String, String, String>> rows = this.cassandraClient.execute(cassandraQuery, family);
    
    for (Row<String, String, String> row : rows) {
      String key = row.getKey();
      
      // find associated row in the resultset
      CassandraRow cassandraRow = cassandraResultSet.getRow(key);
      if (cassandraRow == null) {
        cassandraRow = new CassandraRow();
        cassandraResultSet.putRow(key, cassandraRow);
        cassandraRow.setKey(key);
      }
      
      ColumnSlice<String, String> columnSlice = row.getColumnSlice();
      
      for (HColumn<String, String> hColumn : columnSlice.getColumns()) {
        CassandraSubColumn cassandraSubColumn = new CassandraSubColumn();
        cassandraSubColumn.setValue(hColumn);
        cassandraSubColumn.setFamily(family);
        cassandraRow.add(cassandraSubColumn);
      }
      
    }
  }

  private void addSuperColumns(String family, CassandraQuery<K, T> cassandraQuery, 
      CassandraResultSet cassandraResultSet) {
    
    List<SuperRow<String, String, String, String>> superRows = this.cassandraClient.executeSuper(cassandraQuery, family);
    for (SuperRow<String, String, String, String> superRow: superRows) {
      String key = superRow.getKey();
      CassandraRow cassandraRow = cassandraResultSet.getRow(key);
      if (cassandraRow == null) {
        cassandraRow = new CassandraRow();
        cassandraResultSet.putRow(key, cassandraRow);
        cassandraRow.setKey(key);
      }
      
      SuperSlice<String, String, String> superSlice = superRow.getSuperSlice();
      for (HSuperColumn<String, String, String> hSuperColumn: superSlice.getSuperColumns()) {
        CassandraSuperColumn cassandraSuperColumn = new CassandraSuperColumn();
        cassandraSuperColumn.setValue(hSuperColumn);
        cassandraSuperColumn.setFamily(family);
        cassandraRow.add(cassandraSuperColumn);
      }
    }
  }

  /**
   * Flush the buffer. Write the buffered rows.
   * @see org.apache.gora.store.DataStore#flush()
   */
  @Override
  public void flush() throws IOException {
    
    Set<K> keys = this.buffer.keySet();
    
    // this duplicates memory footprint
    K[] keyArray = (K[]) keys.toArray();
    
    // iterating over the key set directly would throw ConcurrentModificationException with java.util.HashMap and subclasses
    for (K key: keyArray) {
      T value = this.buffer.get(key);
      if (value == null) {
        LOG.info("Value to update is null for key " + key);
        continue;
      }
      Schema schema = value.getSchema();
      for (Field field: schema.getFields()) {
        if (value.isDirty(field.pos())) {
          addOrUpdateField((String) key, field, value.get(field.pos()));
        }
      }
    }
    
    // remove flushed rows
    for (K key: keyArray) {
      this.buffer.remove(key);
    }
  }

  @Override
  public T get(K key, String[] fields) throws IOException {
    LOG.info("get " + key);
    return null;
  }

  @Override
  public List<PartitionQuery<K, T>> getPartitions(Query<K, T> query)
      throws IOException {
    // just a single partition
    List<PartitionQuery<K,T>> partitions = new ArrayList<PartitionQuery<K,T>>();
    partitions.add(new PartitionQueryImpl<K,T>(query));
    return partitions;
  }

  @Override
  public String getSchemaName() {
    LOG.info("get schema name");
    return null;
  }

  @Override
  public Query<K, T> newQuery() {
    return new CassandraQuery<K, T>(this);
  }

  /**
   * Duplicate instance to keep all the objects in memory till flushing.
   * @see org.apache.gora.store.DataStore#put(java.lang.Object, org.apache.gora.persistency.Persistent)
   */
  @Override
  public void put(K key, T value) throws IOException {
    T p = (T) value.newInstance(new StateManagerImpl());
    Schema schema = value.getSchema();
    for (Field field: schema.getFields()) {
      if (value.isDirty(field.pos())) {
        Object fieldValue = value.get(field.pos());
        
        // check if field has a nested structure (map or record)
        Schema fieldSchema = field.schema();
        Type type = fieldSchema.getType();
        switch(type) {
          case RECORD:
            Persistent persistent = (Persistent) fieldValue;
            Persistent newRecord = persistent.newInstance(new StateManagerImpl());
            for (Field member: fieldSchema.getFields()) {
              newRecord.put(member.pos(), persistent.get(member.pos()));
            }
            fieldValue = newRecord;
            break;
          case MAP:
            StatefulHashMap<?, ?> map = (StatefulHashMap<?, ?>) fieldValue;
            StatefulHashMap<?, ?> newMap = new StatefulHashMap(map);
            fieldValue = newMap;
            break;
        }
        
        p.put(field.pos(), fieldValue);
      }
    }
    
    // this performs a structural modification of the map
    this.buffer.put(key, p);
 }

  /**
   * Add a field to Cassandra according to its type.
   * @param key     the key of the row where the field should be added
   * @param field   the Avro field representing a datum
   * @param value   the field value
   */
  private void addOrUpdateField(String key, Field field, Object value) {
    Schema schema = field.schema();
    Type type = schema.getType();
    //LOG.info(field.name() + " " + type.name());
    switch (type) {
      case STRING:
        this.cassandraClient.addColumn(key, field.name(), value);
        break;
      case INT:
        this.cassandraClient.addColumn(key, field.name(), value);
        break;
      case LONG:
        this.cassandraClient.addColumn(key, field.name(), value);
        break;
      case BYTES:
        this.cassandraClient.addColumn(key, field.name(), value);
        break;
      case FLOAT:
        this.cassandraClient.addColumn(key, field.name(), value);
        break;
      case RECORD:
        if (value != null) {
          if (value instanceof PersistentBase) {
            PersistentBase persistentBase = (PersistentBase) value;
            for (Field member: schema.getFields()) {
              
              // TODO: hack, do not store empty arrays
              Object memberValue = persistentBase.get(member.pos());
              if (memberValue instanceof GenericArray<?>) {
                GenericArray<String> array = (GenericArray<String>) memberValue;
                if (array.size() == 0) {
                  continue;
                }
              }
              
              this.cassandraClient.addSubColumn(key, field.name(), member.name(), memberValue);
            }
          } else {
            LOG.info("Record not supported: " + value.toString());
            
          }
        }
        break;
      case MAP:
        if (value != null) {
          if (value instanceof StatefulHashMap<?, ?>) {
            //TODO cast to stateful map and only write dirty keys
            Map<Utf8, Object> map = (Map<Utf8, Object>) value;
            for (Utf8 mapKey: map.keySet()) {
              
              // TODO: hack, do not store empty arrays
              Object keyValue = map.get(mapKey);
              if (keyValue instanceof GenericArray<?>) {
                GenericArray<String> array = (GenericArray<String>) keyValue;
                if (array.size() == 0) {
                  continue;
                }
              }
              
              this.cassandraClient.addSubColumn(key, field.name(), mapKey.toString(), keyValue);              
            }
          } else {
            LOG.info("Map not supported: " + value.toString());
          }
        }
        break;
      default:
        LOG.info("Type not considered: " + type.name());      
    }
  }

  @Override
  public boolean schemaExists() throws IOException {
    LOG.info("schema exists");
    return false;
  }

}
