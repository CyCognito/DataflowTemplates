/*
 * Copyright (C) 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.bigtable;

import com.google.bigtable.v2.RowFilter;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.avro.coders.AvroCoder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO;
import org.apache.beam.sdk.io.parquet.ParquetIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;

/**
 * Dataflow pipeline that exports data from a Cloud Bigtable table to Parquet files in GCS. Only the
 * last cell version is taken
 */
public class BigtableToParquetLastVerOnly extends BigtableToParquet {


  /**
   * Main entry point for pipeline execution.
   *
   * @param args Command line arguments to the pipeline.
   */
  public static void main(String[] args) {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
        RowFilter filter = RowFilter.newBuilder()
                .setCellsPerColumnLimitFilter(1).build();

        PipelineResult result = run(options, filter);

    // Wait for pipeline to finish only if it is not constructing a template.
    if (options.as(DataflowPipelineOptions.class).getTemplateLocation() == null) {
      result.waitUntilFinish();
    }
  }

  /**
   * Runs a pipeline to export data from a Cloud Bigtable table to Parquet file(s) in GCS.
   *
   * @param options arguments to the pipeline
   */
  public static PipelineResult run(Options options) {
    Pipeline pipeline = Pipeline.create(PipelineUtils.tweakPipelineOptions(options));
    BigtableIO.Read read =
        BigtableIO.read()
            .withProjectId(options.getBigtableProjectId())
            .withInstanceId(options.getBigtableInstanceId())
            .withTableId(options.getBigtableTableId())
            .withRowFilter(filter);

    // Do not validate input fields if it is running as a template.
    if (options.as(DataflowPipelineOptions.class).getTemplateLocation() != null) {
      read = read.withoutValidation();
    }

    /**
     * Steps:
     * 1) Read records from Bigtable.
     * 2) Convert a Bigtable Row to a GenericRecord.
     * 3) Write GenericRecord(s) to GCS in parquet format.
     */
    pipeline
        .apply("Read from Bigtable", read)
        .apply("Transform to Parquet", MapElements.via(new BigtableToParquetFn()))
        .setCoder(AvroCoder.of(GenericRecord.class, BigtableRow.getClassSchema()))
        .apply(
            "Write to Parquet in GCS",
            FileIO.<GenericRecord>write()
                .via(ParquetIO.sink(BigtableRow.getClassSchema()))
                .to(options.getOutputDirectory())
                .withPrefix(options.getFilenamePrefix())
                                .withSuffix(".parquet")
                                .withNumShards(options.getNumShards()));

    return pipeline.run();
  }
}
