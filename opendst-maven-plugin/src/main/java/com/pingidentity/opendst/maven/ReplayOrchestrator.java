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
package com.pingidentity.opendst.maven;

import static java.lang.System.err;

import com.pingidentity.opendst.maven.AcoOrchestrator.LogStatement;
import tools.jackson.core.JacksonException;
import tools.jackson.jr.ob.JSON;

public final class ReplayOrchestrator implements Orchestrator {
    private final Plan plan;
    private String lastLogOrNull;
    private boolean done;

    public ReplayOrchestrator(Plan plan) {
        this.plan = plan;
    }

    @Override
    public Plan nextPlan() {
        return plan;
    }

    @Override
    public void onLogReceived(Plan plan, String logLine) {
        try {
            var log = JSON.std.beanFrom(LogStatement.class, logLine);
            if (log.isEndOfSimulation()) {
                lastLogOrNull = logLine;
            }
        } catch (JacksonException e) {
            // Ignore badly formatted logs
        }
    }

    @Override
    public void onPlanTerminated(Plan plan, int code) {
        if (code == 1) {
            err.printf("The simulation has found a problem: %s%n", lastLogOrNull);
        } else if (code != 0) {
            err.printf("The simulation has failed unexpectedly: %s%n", lastLogOrNull);
        }
    }
}
