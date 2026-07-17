/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES FROM THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit Oracle, http://www.oracle.com, if you need additional information
 * or have any questions.
 */
package org.openjdk.bench.sun.misc;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import jdk.jfr.Recording;
import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Measures the performance impact of emitting JFR events for native memory
 * operations on {@code jdk.internal.misc.Unsafe}. The benchmark runs both
 * with JFR disabled (the default state) and with a {@link Recording} that
 * has the three native-memory events enabled, so the cost of the event
 * emission can be quantified.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 3, jvmArgs = {"-Xms1g", "-Xmx1g"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@SuppressWarnings("removal")
public class UnsafeMemoryEvents {

    private static final Unsafe U;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot access Unsafe", ex);
        }
    }

    @Param({"64", "1024"})
    public int size;

    /**
     * If {@code true}, a {@link Recording} is started that enables the three
     * native memory events. If {@code false}, no recording is active,
     * which measures the JFR-disabled (zero overhead) baseline.
     */
    @Param({"false", "true"})
    public boolean eventsEnabled;

    @Param({"false", "true"})
    public boolean stackTraceEnabled;

    private Recording recording;

    @Setup
    public void setup(BenchmarkParams params) {
        if (eventsEnabled) {
            recording = new Recording();
            recording.setToDisk(false);
            String benchmark = params.getBenchmark();
            if (benchmark.endsWith(".allocateMemory")) {
                var settings = recording.enable("jdk.NativeMemoryAllocation").withoutThreshold();
                if (stackTraceEnabled) {
                    settings.withStackTrace();
                } else {
                    settings.withoutStackTrace();
                }
            } else if (benchmark.endsWith(".freeMemory")) {
                var settings = recording.enable("jdk.NativeMemoryFree").withoutThreshold();
                if (stackTraceEnabled) {
                    settings.withStackTrace();
                } else {
                    settings.withoutStackTrace();
                }
            } else if (benchmark.endsWith(".reallocateMemory")) {
                var settings = recording.enable("jdk.NativeMemoryReallocate").withoutThreshold();
                if (stackTraceEnabled) {
                    settings.withStackTrace();
                } else {
                    settings.withoutStackTrace();
                }
            } else {
                throw new IllegalStateException("Unexpected benchmark: " + benchmark);
            }
            recording.start();
        }
    }

    @TearDown
    public void tearDown() {
        if (recording != null) {
            recording.stop();
            recording.close();
        }
    }

    @Benchmark
    public long allocateMemory() {
        long addr = U.allocateMemory(size);
        U.freeMemory(addr);
        return addr;
    }

    @Benchmark
    public long reallocateMemory() {
        long addr = U.allocateMemory(size);
        long reallocAddr = U.reallocateMemory(addr, size);
        U.freeMemory(reallocAddr);
        return reallocAddr;
    }

    @Benchmark
    public long freeMemory() {
        long addr = U.allocateMemory(size);
        U.freeMemory(addr);
        return addr;
    }
}
