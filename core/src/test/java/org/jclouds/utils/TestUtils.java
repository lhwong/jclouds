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
package org.jclouds.utils;

import java.io.InputStream;
import java.io.IOException;
import java.util.Random;

import com.google.common.io.ByteSource;

/**
 * Utility class for test
 */
public class TestUtils {
    public static final Object[][] NO_INVOCATIONS = new Object[0][0];
    public static final Object[][] SINGLE_NO_ARG_INVOCATION = { new Object[0] };

    public static boolean isJava6() {
        System.out.println(System.getProperty("java.version", "None??"));
        return System.getProperty("java.version", "").contains("1.6.");
    }

    public static boolean isJava7() {
        System.out.println(System.getProperty("java.version", "None??"));
        return System.getProperty("java.version", "").contains("1.7.");
    }

    public static boolean isJava8() {
        System.out.println(System.getProperty("java.version", "None??"));
        return System.getProperty("java.version", "").contains("1.8.");
    }

    public static ByteSource randomByteSource() {
        return randomByteSource(0);
    }

    public static ByteSource randomByteSource(long seed) {
        return new RandomByteSource(seed);
    }

    private static class RandomByteSource extends ByteSource {
        private final long seed;

        RandomByteSource(long seed) {
            this.seed = seed;
        }

        @Override
        public InputStream openStream() {
            return new RandomInputStream(seed);
        }
    }

    private static class RandomInputStream extends InputStream {
        private final Random random;

        RandomInputStream(long seed) {
           this.random = new Random(seed);
        }

        @Override
        public synchronized int read() {
            return (byte) random.nextInt();
        }

        @Override
        public synchronized int read(byte[] b) throws IOException {
            random.nextBytes(b);
            return b.length;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            // need extra array to cope with offset
            byte[] srcBytes = new byte[len];
            random.nextBytes(srcBytes);
            System.arraycopy(srcBytes, 0, b, off, len);
            return len;
        }
    }
}
