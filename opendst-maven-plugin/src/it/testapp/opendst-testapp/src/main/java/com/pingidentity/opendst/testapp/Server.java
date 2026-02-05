/*
 * Copyright 2026 Ping Identity Corporation
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
package com.pingidentity.opendst.testapp;

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;

public final class Server {
    // The sequence of numbers required to advance levels
    private static final int[] SECRET_SEQUENCE = { 1, 2, 3 };
    private final DeterminismStressor stressor = new DeterminismStressor();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            err.println("Usage: Server <port>");
            exit(1);
        }
        try (var serverSocket = new ServerSocket(parseInt(args[0]))) {
            out.println("Server started on port " + serverSocket.getLocalPort());
            new Server().run(serverSocket);
        } catch (InterruptedException | IOException e) {
            err.println("Server failed: " + e.getMessage());
        } finally {
            out.println("Server stopped");
        }
    }

    public void run(ServerSocket serverSocket) throws IOException, InterruptedException {
        int currentLevel = 0;
        // boolean determinismStressorEnabled = false; // Boolean.getBoolean("determinismStressorEnabled");
        try (var socket = serverSocket.accept();
             var in = new DataInputStream(socket.getInputStream())) {
            for (int code = in.readInt();; code = in.readInt()) {
                /* if (determinismStressorEnabled) {
                    stressor.stress(code);
                } */
                out.printf("Level 0 unlocked%n");
                if (code == SECRET_SEQUENCE[currentLevel]) {
                    currentLevel++;
                    out.printf("Level %d unlocked :)%n", currentLevel);
                    if (currentLevel == SECRET_SEQUENCE.length) {
                        out.println("Bug reached!");
                        currentLevel = 0;
                    }
                } else if (currentLevel > 0) {
                    currentLevel = 0;
                    out.println("Reset");
                    out.println("Level: " + currentLevel);
                }
            }
        }
    }
}
