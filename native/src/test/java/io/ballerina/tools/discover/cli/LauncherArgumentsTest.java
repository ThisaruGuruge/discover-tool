/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.tools.discover.cli;

import org.testng.Assert;
import org.testng.annotations.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

/**
 * The arguments {@code bal}'s launcher hands the tool, parsed the way the launcher parses them: a root command that
 * stops at its first positional, with the tool added as a subcommand and then told its parent.
 *
 * @since 0.1.0
 */
public class LauncherArgumentsTest {

    private static final String KAFKA = "ballerinax/kafka";

    @CommandLine.Command(name = "bal")
    static final class Launcher {
    }

    private static List<String> handedOver(String... args) {
        DiscoverTool tool = new DiscoverTool(System.out, System.err);
        CommandLine launcher = new CommandLine(new Launcher());
        launcher.setStopAtPositional(true);
        launcher.addSubcommand(tool.getName(), tool);
        tool.setParentCmdParser(launcher);
        launcher.setPosixClusteredShortOptionsAllowed(false);
        launcher.parseArgs(args);
        return tool.arguments();
    }

    @Test
    public void aFlagBeforeThePackageReachesTheTool() {
        Assert.assertEquals(handedOver("discover", "--output", "json", KAFKA, "client", "Producer"),
                List.of("--output", "json", KAFKA, "client", "Producer"));
    }

    @Test
    public void everyFlagBeforeThePackageReachesTheTool() {
        List<String> argv = List.of("--output", "text", "--module", "other", "--filter", "send", "--page", "2",
                "--refresh", "-m", "other", KAFKA, "client");
        List<String> withLauncher = new ArrayList<>(List.of("discover"));
        withLauncher.addAll(argv);
        Assert.assertEquals(handedOver(withLauncher.toArray(new String[0])), argv);
    }

    @Test
    public void anOptionWithItsValueAttachedReachesTheTool() {
        Assert.assertEquals(handedOver("discover", "--output=json", KAFKA, "--filter=send"),
                List.of("--output=json", KAFKA, "--filter=send"));
    }

    @Test
    public void flagsAfterThePackageKeepTheirOrder() {
        Assert.assertEquals(handedOver("discover", KAFKA, "client", "--page", "2", "-m", "other"),
                List.of(KAFKA, "client", "--page", "2", "-m", "other"));
    }

    @Test
    public void aLeadingHelpFlagIsPutBackLast() {
        Assert.assertEquals(handedOver("discover", "--help", KAFKA), List.of(KAFKA, "--help"));
    }

    @Test
    public void theGrammarReadsFlagsBeforeThePackage() {
        Commands.Grammar grammar = Commands.Grammar.create();
        grammar.line().parseArgs(handedOver("discover", "--output", "json", "--module", "other", "--filter", "send",
                "--page", "2", "--refresh", KAFKA, "client").toArray(new String[0]));
        Commands.Root root = grammar.root();
        Assert.assertEquals(root.output, "json");
        Assert.assertEquals(root.module, "other");
        Assert.assertEquals(root.filter, "send");
        Assert.assertEquals(root.page, 2);
        Assert.assertTrue(root.refresh);
        Assert.assertEquals(root.pkg, KAFKA);
        Assert.assertEquals(root.rest, List.of("client"));
    }
}
