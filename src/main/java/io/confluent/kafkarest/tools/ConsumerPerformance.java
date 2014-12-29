/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafkarest.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.Versions;
import io.confluent.kafkarest.entities.ConsumerInstanceConfig;
import io.confluent.kafkarest.entities.CreateConsumerInstanceResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Random;

public class ConsumerPerformance extends AbstractPerformanceTest {
    long targetRecords;
    long recordsPerSec;
    ObjectMapper serializer = new ObjectMapper();
    String targetUrl;
    String deleteUrl;
    long consumedRecords = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println(
                    "Usage: java " + ConsumerPerformance.class.getName() + " rest_url topic_name " +
                            "num_records target_records_sec"
            );
            System.exit(1);
        }

        String baseUrl = args[0];
        String topic = args[1];
        int numRecords = Integer.parseInt(args[2]);
        int throughput = Integer.parseInt(args[3]);

        ConsumerPerformance perf = new ConsumerPerformance(baseUrl, topic, numRecords, throughput);
        // We need an approximate # of iterations per second, but we don't know how many records per request we'll receive
        // so we don't know how many iterations per second we need to hit the target rate. Get an approximate value using
        // the default max # of records per request the server will return.
        perf.run(throughput / Integer.parseInt(KafkaRestConfig.CONSUMER_REQUEST_MAX_MESSAGES_DEFAULT));
        perf.close();
    }

    public ConsumerPerformance(String baseUrl, String topic, long numRecords, long recordsPerSec) throws Exception {
        super(numRecords);
        this.targetRecords = numRecords;
        this.recordsPerSec = recordsPerSec;

        String groupId = "rest-perf-consumer-" + Integer.toString(new Random().nextInt(100000));

        // Create consumer instance
        ConsumerInstanceConfig consumerConfig = new ConsumerInstanceConfig();
        consumerConfig.setAutoOffsetReset("smallest");
        byte[] createPayload = serializer.writeValueAsBytes(consumerConfig);
        CreateConsumerInstanceResponse createResponse = (CreateConsumerInstanceResponse) request(
                baseUrl + "/consumers/" + groupId, "POST", createPayload, Integer.toString(createPayload.length),
                new TypeReference<CreateConsumerInstanceResponse>() {
                }
        );

        targetUrl = baseUrl + "/consumers/" + groupId + "/instances/" + createResponse.getInstanceId() + "/topics/" + topic;
        deleteUrl = baseUrl + "/consumers/" + groupId + "/instances/" + createResponse.getInstanceId();

        // Run a single read request and ignore the result to get started. This makes sure the consumer on the REST proxy
        // is fully setup and connected.
        request(targetUrl, "GET", null, null, new TypeReference<List<UndecodedConsumerRecord>>() {});
    }

    @Override
    protected void doIteration(PerformanceStats.Callback cb) {
        List<UndecodedConsumerRecord> records = request(targetUrl, "GET", null, null, new TypeReference<List<UndecodedConsumerRecord>>() {});
        long bytes = 0;
        for (UndecodedConsumerRecord record : records)
            bytes += record.value.length() * 3 / 4;
        consumedRecords += records.size();
        cb.onCompletion(records.size(), bytes);
    }

    protected void close() {
        request(deleteUrl, "DELETE", null, null, null);
    }

    private <T> T request(String target, String method, byte[] entity, String entityLength, TypeReference<T> responseFormat) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(target);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            if (entity != null) {
                connection.setRequestProperty("Content-Type", Versions.KAFKA_MOST_SPECIFIC_DEFAULT);
                connection.setRequestProperty("Content-Length", entityLength);
                connection.setDoInput(true);
            }

            connection.setUseCaches(false);
            if (method != "DELETE") {
                connection.setDoOutput(true);
            }

            if (entity != null) {
                OutputStream os = connection.getOutputStream();
                os.write(entity);
                os.flush();
                os.close();
            }

            if (method != "DELETE") {
                InputStream is = connection.getInputStream();
                T result = serializer.readValue(is, responseFormat);
                is.close();
                return result;
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    protected boolean finished(int iteration) {
        return consumedRecords >= targetRecords;
    }

    @Override
    protected boolean runningSlow(int iteration, float elapsed) {
        return (consumedRecords/elapsed < recordsPerSec);
    }

    // This version of ConsumerRecord has the same basic format, but leaves the data encoded since we only need to get
    // the size of each record.
    private static class UndecodedConsumerRecord {
        public String key;
        public String value;
        public int partition;
        public long offset;
    }
}

