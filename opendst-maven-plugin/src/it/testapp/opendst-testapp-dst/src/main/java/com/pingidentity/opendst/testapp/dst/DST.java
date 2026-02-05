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
package com.pingidentity.opendst.testapp.dst;

import static com.pingidentity.opendst.Deployment.Image.image;
import static com.pingidentity.opendst.Deployment.Service.service;
import static com.pingidentity.opendst.Deployment.deployment;
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.net.InetAddress.ofLiteral;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;

import com.pingidentity.opendst.LogProcessor;

public class DST {
    public static final Path WAR = Path.of("opendst-testapp");

    public void run() throws IOException {
        runSimulation(
                deployment(
                        of(image("server-image", WAR, "com.pingidentity.opendst.testapp.Server"),
                           image("client-image", WAR, "com.pingidentity.opendst.testapp.Client")),
                        of(service("server", "server-image", ofLiteral("10.0.0.2"), new String[]{ "8080"}),
                           service("client", "client-image", ofLiteral("10.0.0.3"), new String[]{"10.0.0.2", "8080"}))),
                log -> assertThat(log.message())
                        .describedAs("Bug detected by the log-processor")
                        .doesNotContain("Bug reached!"));
    }
}
