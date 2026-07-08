/*
 * Copyright 2024-2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.intercept;

import static com.pingidentity.opendst.simulator.Node.currentNodeOrNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.pingidentity.opendst.simulator.Node;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.asm.Advice.This;

/**
 * Intercepts {@link FileSystem#newWatchService()} and {@link Path#register} to replace the native
 * OS watcher with no-op implementations when running inside a simulation node, preventing
 * context-free platform threads from being spawned.
 */
public final class FileSystemInterceptors {

    /**
     * Overrides {@link FileSystem#newWatchService()} inside a simulation node to return a
     * {@link NoOpWatchService} instead of the native OS watcher.
     */
    public static final class FileSystemNewWatchServiceAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ReassignedVariable", "ParameterCanBeLocal", "UnusedAssignment"})
        public static void onExit(@Enter Node node, @Return(readOnly = false) WatchService service) {
            if (node != null) {
                service = new NoOpWatchService();
            }
        }

        /** A no-op {@link WatchService} that never starts any threads and always returns null keys. */
        public static final class NoOpWatchService implements WatchService {
            private final CountDownLatch closeLatch = new CountDownLatch(1);
            private volatile boolean closed;

            @Override
            public void close() {
                closed = true;
                closeLatch.countDown();
            }

            @Override
            public WatchKey poll() {
                if (closed) {
                    throw new ClosedWatchServiceException();
                }
                return null;
            }

            @Override
            public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
                closeLatch.await(timeout, unit);
                if (closed) {
                    throw new ClosedWatchServiceException();
                }
                return null;
            }

            @Override
            public WatchKey take() throws InterruptedException {
                closeLatch.await();
                throw new ClosedWatchServiceException();
            }
        }
    }

    /**
     * Overrides {@link Path#register(WatchService, Kind[])} and
     * {@link Path#register(WatchService, Kind[], Modifier...)} to return a {@link NoOpWatchKey}
     * when inside a simulation node.
     */
    public static final class PathRegisterAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ReassignedVariable", "ParameterCanBeLocal", "UnusedAssignment"})
        public static void onExit(@Enter Node node, @This Path path, @Return(readOnly = false) WatchKey key) {
            if (node != null) {
                key = new NoOpWatchKey(path);
            }
        }

        /** A no-op {@link WatchKey} that is always valid and always returns an empty event list. */
        public static final class NoOpWatchKey implements WatchKey {
            private final Watchable watchable;
            private boolean valid = true;

            public NoOpWatchKey(Watchable watchable) {
                this.watchable = watchable;
            }

            @Override
            public boolean isValid() {
                return valid;
            }

            @Override
            public List<WatchEvent<?>> pollEvents() {
                return List.of();
            }

            @Override
            public boolean reset() {
                return valid;
            }

            @Override
            public void cancel() {
                valid = false;
            }

            @Override
            public Watchable watchable() {
                return watchable;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(isSubTypeOf(FileSystem.class))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(FileSystemNewWatchServiceAdvice.class).on(named("newWatchService"))))
                .asTerminalTransformation()
                .type(isSubTypeOf(Path.class))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(PathRegisterAdvice.class).on(named("register"))))
                .asTerminalTransformation();
    }

    private FileSystemInterceptors() {}
}
