/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
package jdk.jfr.event.runtime;

import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.test.lib.Asserts;
import jdk.test.lib.jfr.EventNames;
import jdk.test.lib.jfr.Events;

/**
 * @test
 * @key jfr
 * @requires vm.hasJFR
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main/othervm jdk.jfr.event.runtime.TestNativeMemoryEvent
 */
public class TestNativeMemoryEvent {

    private static final String ALLOC_EVENT = EventNames.NativeMemoryAllocation;
    private static final String FREE_EVENT = EventNames.NativeMemoryFree;
    private static final String REALLOC_EVENT = EventNames.NativeMemoryReallocate;

    public static void main(String[] args) throws Throwable {
        testBasicFlow();
        testZeroBytesAllocate();
        testReallocateFromZero();
        testEventsDisabledByDefault();
    }

    private static void testBasicFlow() throws Throwable {
        Unsafe unsafe = Unsafe.getUnsafe();

        try (Recording r = new Recording()) {
            r.enable(ALLOC_EVENT);
            r.enable(FREE_EVENT);
            r.enable(REALLOC_EVENT);
            r.start();

            long addr = unsafe.allocateMemory(100);
            long reallocAddr = unsafe.reallocateMemory(addr, 1000);
            unsafe.freeMemory(reallocAddr);

            r.stop();

            List<RecordedEvent> events = Events.fromRecording(r);
            Events.hasEvents(events);

            boolean foundAlloc = false;
            boolean foundFree = false;
            boolean foundRealloc = false;

            for (RecordedEvent event : events) {
                String name = event.getEventType().getName();
                switch (name) {
                    case "jdk.NativeMemoryAllocation" -> {
                        long size = Events.assertField(event, "size").atLeast(1L).getValue();
                        long address = Events.assertField(event, "address").atLeast(1L).getValue();
                        Asserts.assertEquals(address, addr, "Allocation address mismatch");
                        // size records the original requested bytes before heap word alignment
                        Asserts.assertEquals(size, 100L, "Allocation size should be requested bytes");
                        foundAlloc = true;
                    }
                    case "jdk.NativeMemoryReallocate" -> {
                        long oldAddr = Events.assertField(event, "oldAddress").atLeast(1L).getValue();
                        long newAddr = Events.assertField(event, "newAddress").atLeast(1L).getValue();
                        long size = Events.assertField(event, "size").atLeast(1L).getValue();
                        Asserts.assertEquals(oldAddr, addr, "Reallocate old address mismatch");
                        Asserts.assertEquals(newAddr, reallocAddr, "Reallocate new address mismatch");
                        // size records the original requested bytes before heap word alignment
                        Asserts.assertEquals(size, 1000L, "Reallocate size should be requested bytes");
                        foundRealloc = true;
                    }
                    case "jdk.NativeMemoryFree" -> {
                        long freedAddr = Events.assertField(event, "address").atLeast(1L).getValue();
                        Asserts.assertEquals(freedAddr, reallocAddr, "Free address mismatch");
                        foundFree = true;
                    }
                    default -> { /* ignore other events */ }
                }
            }

            Asserts.assertTrue(foundAlloc, "Missing NativeMemoryAllocation event");
            Asserts.assertTrue(foundRealloc, "Missing NativeMemoryReallocate event");
            Asserts.assertTrue(foundFree, "Missing NativeMemoryFree event");
        }
    }

    private static void testZeroBytesAllocate() throws Throwable {
        Unsafe unsafe = Unsafe.getUnsafe();

        try (Recording r = new Recording()) {
            r.enable(ALLOC_EVENT);
            r.start();

            // allocateMemory(0) returns 0 and should not produce an event
            long addr = unsafe.allocateMemory(0);
            Asserts.assertEquals(addr, 0L, "allocateMemory(0) should return 0");

            r.stop();

            List<RecordedEvent> events = Events.fromRecording(r);
            for (RecordedEvent event : events) {
                Asserts.assertNotEquals(event.getEventType().getName(), ALLOC_EVENT,
                    "allocateMemory(0) should not produce an allocation event");
            }
        }
    }

    private static void testReallocateFromZero() throws Throwable {
        Unsafe unsafe = Unsafe.getUnsafe();

        try (Recording r = new Recording()) {
            r.enable(REALLOC_EVENT);
            r.start();

            // reallocateMemory(0, size) is equivalent to allocateMemory(size)
            long addr = unsafe.reallocateMemory(0, 128);
            try {
                Asserts.assertGreaterThan(addr, 0L, "reallocateMemory(0, 128) should return non-zero");
            } finally {
                unsafe.freeMemory(addr);
            }

            r.stop();

            List<RecordedEvent> events = Events.fromRecording(r);
            Events.hasEvents(events);

            RecordedEvent event = events.getFirst();
            Asserts.assertEquals(event.getEventType().getName(), REALLOC_EVENT,
                "Expected NativeMemoryReallocate event");
            long oldAddr = Events.assertField(event, "oldAddress").getValue();
            Asserts.assertEquals(oldAddr, 0L, "oldAddress should be 0 for reallocate from null");
        }
    }

    private static void testEventsDisabledByDefault() throws Throwable {
        Unsafe unsafe = Unsafe.getUnsafe();

        try (Recording r = new Recording()) {
            // Don't explicitly enable NativeMemory events — they should be disabled by default
            r.start();

            long addr = unsafe.allocateMemory(100);
            unsafe.freeMemory(addr);

            r.stop();

            List<RecordedEvent> events = Events.fromRecording(r);
            for (RecordedEvent event : events) {
                String name = event.getEventType().getName();
                Asserts.assertNotEquals(name, ALLOC_EVENT,
                    "NativeMemoryAllocation should be disabled by default");
                Asserts.assertNotEquals(name, FREE_EVENT,
                    "NativeMemoryFree should be disabled by default");
                Asserts.assertNotEquals(name, REALLOC_EVENT,
                    "NativeMemoryReallocate should be disabled by default");
            }
        }
    }
}
