/*
 *  Copyright 2017 Datamountaineer.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.datamountaineer.streamreactor.connect.converters.source

import java.io.File
import java.util.Collections

import com.datamountaineer.streamreactor.connect.converters.MsgKey
import io.confluent.connect.avro.AvroData
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory
import org.apache.avro.{Schema => AvroSchema}
import org.apache.kafka.connect.data.{Schema, Struct}
import org.apache.kafka.connect.source.SourceRecord
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException


class AvroConverter extends Converter {
  private val avroData = new AvroData(8)
  private var sourceToSchemaMap: Map[String, AvroSchema] = Map.empty
  private var avroReadersMap: Map[String, GenericDatumReader[GenericRecord]] = Map.empty

  override def convert(kafkaTopic: String,
                       sourceTopic: String,
                       messageId: String,
                       bytes: Array[Byte],
                       properties: Map[String, String] = Map.empty[String, String],
                       keys: Seq[String] = Seq.empty,
                       keyDelimiter: String = "."): SourceRecord = {
    Option(bytes) match {
      case None =>
        new SourceRecord(Collections.singletonMap(Converter.TopicKey, sourceTopic),
          null,
          kafkaTopic,
          avroData.toConnectSchema(sourceToSchemaMap(sourceTopic)),
          null)
      case Some(_) =>
        val reader = avroReadersMap.getOrElse(sourceTopic.toLowerCase, throw new ConfigException(s"Invalid ${AvroConverter.SCHEMA_CONFIG} is not configured for $sourceTopic"))
        val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
        val record = reader.read(null, decoder)
        val schemaAndValue = avroData.toConnectData(sourceToSchemaMap(sourceTopic.toLowerCase), record)
        val value = schemaAndValue.value()
        value match {
          case s: Struct if keys.nonEmpty =>
            val keysValue = keys.flatMap { key =>
              Option(KeyExtractor.extract(s, key.split('.').toVector)).map(_.toString)
            }.mkString(keyDelimiter)
            new SourceRecord(
              Collections.singletonMap(Converter.TopicKey, sourceTopic),
              null,
              kafkaTopic,
              Schema.STRING_SCHEMA,
              keysValue,
              schemaAndValue.schema(),
              schemaAndValue.value())
          case _ =>
            new SourceRecord(
              Collections.singletonMap(Converter.TopicKey, sourceTopic),
              null,
              kafkaTopic,
              MsgKey.schema,
              MsgKey.getStruct(sourceTopic, messageId),
              schemaAndValue.schema(),
              schemaAndValue.value())
        }

    }
  }

  override def initialize(config: Map[String, String]): Unit = {
    sourceToSchemaMap = AvroConverter.getSchemas(config)
    avroReadersMap = sourceToSchemaMap.map { case (key, schema) =>
      key -> new GenericDatumReader[GenericRecord](schema)
    }
  }
}

object AvroConverter {
  val SCHEMA_CONFIG = "connect.source.converter.avro.schemas"

  def getSchemas(config: Map[String, String]): Map[String, AvroSchema] = {
    config.getOrElse(SCHEMA_CONFIG, throw new ConfigException(s"$SCHEMA_CONFIG is not provided"))
      .toString
      .split(';')
      .filter(_.trim.nonEmpty)
      .map(_.split("="))
      .map {
        case Array(source, path) =>
          val file = new File(path)
          if (!file.exists()) {
            throw new ConfigException(s"Invalid $SCHEMA_CONFIG. The file $path doesn't exist!")
          }
          val s = source.trim.toLowerCase()
          if (s.isEmpty) {
            throw new ConfigException(s"Invalid $SCHEMA_CONFIG. The topic is not valid for entry containing $path")
          }
          s -> new AvroSchema.Parser().parse(file)
        case other => throw new ConfigException(s"$SCHEMA_CONFIG is not properly set. The format is Mqtt_Source->AVRO_FILE")
      }.toMap
  }
}
