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

import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Loader;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Overview;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Search;
import io.ballerina.tools.discover.views.SearchCentral;
import io.ballerina.tools.discover.views.Types;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@code bal discover} — argv in, exit code out.
 *
 * <p>The stream discipline is the contract:
 *
 * <ul>
 *   <li>stdout — the requested document, and nothing else: no progress, no banner. A usage request is a request
 *       like any other, so {@code --help} is that document
 *   <li>stderr — on failure, one {@link Failure} and nothing else, in the run's output mode: one JSON object,
 *       or {@code error: <message>} with the suggestion indented below it
 *   <li>exit 0 — success, and stdout is COMPLETE
 *   <li>any other exit — a failure, one code per action the caller takes next ({@link Failure#exitCode()}). A usage
 *       error found before anything is looked up is followed, in text mode, by the synopsis and a pointer to
 *       {@code --help}
 * </ul>
 *
 * <p>{@code bal discover <org>/<name>[:<version>] [bucket] [name ...]} is one picocli command, and {@code bucket} a
 * plain positional value rather than a subcommand: package, then bucket, then member is one drill-down, not a mode
 * switch.
 *
 * <p>No {@link System#exit} here, and no environment read: the cache, the transport and the project directory
 * arrive as arguments, so a test drives the real command against a recorded payload and a temporary directory.
 * {@link DiscoverTool} is the only place that touches the process.
 *
 * @since 0.1.0
 */
public final class Cli {

    private static final String OUTPUT_FLAG = "--output";

    private static final String HELP = Coordinate.COMMAND + "--help";

    private static final String RUN_HELP = "Run `" + HELP + "` for usage.";

    private static final int FIRST_PAGE = 1;

    private static final String PLACEHOLDER_PACKAGE = "<org>/<name>";

    private static final String PLACEHOLDER_VERSION = "<version>";

    private static final String PLACEHOLDER_KEYWORD = "<keyword>";

    private static final String WRITE_VERSION = "To read a specific version, write it after the package: ";

    private static final String A_RESOURCE_METHOD = "a resource method";

    private static final String A_METHOD = "a method";

    /** Plurals and other spellings typed for a bucket. */
    private static final Map<String, String> BUCKET_ALIASES = Map.of(
            "func", Surface.Scope.MODULE.verb(), "function", Surface.Scope.MODULE.verb(),
            "functions", Surface.Scope.MODULE.verb(), "clients", Surface.Scope.CLIENT.verb(),
            "services", Surface.Scope.SERVICE.verb(), "classes", Surface.Scope.CLASS.verb(),
            "types", Types.BUCKET);

    /** Words for the package itself, whose overview is the package with no bucket. */
    private static final Set<String> PACKAGE_WORDS = Set.of("package", "packages");

    /** Words for a member, which is read under its container's bucket, and the member each names. */
    private static final Map<String, String> MEMBER_WORDS = Map.of(
            "method", A_METHOD, "methods", A_METHOD, "resource", A_RESOURCE_METHOD, "resources", A_RESOURCE_METHOD);

    private static final String CONTAINER_BUCKETS = Surface.Scope.CLIENT.verb() + ", "
            + Surface.Scope.SERVICE.verb() + " or " + Surface.Scope.CLASS.verb();

    private Cli() {
    }

    /**
     * Where the two streams go. Injected so a test can capture both without a subprocess.
     *
     * @param out where stdout goes
     * @param errorOut where stderr goes
     */
    public record Streams(Consumer<String> out, Consumer<String> errorOut) { }

    public static int run(List<String> argv, Streams streams, HttpOptions http) {
        return run(argv, streams, http, null);
    }

    /**
     * @param projectDir the Ballerina project the process is standing in, or {@code null}. Discovered by
     *     {@link DiscoverTool}, never by a flag — see {@link Commands}
     */
    public static int run(List<String> argv, Streams streams, HttpOptions http, String projectDir) {
        return run(argv, streams, http, projectDir, false);
    }

    /**
     * @param interactive whether stdout is an interactive terminal — the {@code --output} default in the absence
     *     of the flag. Discovered by {@link DiscoverTool} from {@code System.console()}, never read here: a test
     *     drives both defaults without a real terminal, the same reason the cache and the transport are injected.
     */
    public static int run(
            List<String> argv, Streams streams, HttpOptions http, String projectDir, boolean interactive) {
        if (argv.isEmpty()) {
            streams.out().accept(Usage.root());
            return Failure.ANSWERED;
        }

        Commands.Grammar grammar = Commands.Grammar.create();
        boolean json = jsonOutput(argv, interactive, grammar);
        UsageErrors usage = new UsageErrors(grammar, streams, json);
        CommandLine.ParseResult parsed;
        try {
            parsed = grammar.line().parseArgs(argv.toArray(new String[0]));
        } catch (CommandLine.ParameterException cause) {
            // picocli's own error printing is deliberately never used: stderr has to hold exactly one `Failure`
            // object, and picocli would write a usage block beside it.
            Failure.Validation specific = describeParseError(cause, grammar);
            return specific == null
                    ? usage.helpError(firstLine(cause.getMessage()))
                    : usage.error(specific);
        }

        // A usage request is answered, not failed: it goes to stdout at exit 0, because "exit 0 means stdout is
        // complete" is what a redirecting caller relies on.
        if (parsed.isUsageHelpRequested()) {
            streams.out().accept(Usage.root());
            return Failure.ANSWERED;
        }

        Commands.Root root = grammar.root();
        if (root.version != null) {
            return usage.error(versionFlag(root));
        }
        if (SearchCentral.VERB.equals(root.pkg)) {
            return searchCentral(root, streams, http, json, usage);
        }
        if (root.pkg == null) {
            return usage.error(new Failure.Validation(
                    "A package is required.",
                    "Pass '<org>/<name>', e.g. bal discover ballerinax/github."));
        }

        Failure.Validation bucketFirst = bucketBeforePackage(root);
        if (bucketFirst != null) {
            return usage.error(bucketFirst);
        }

        Result<Coordinate> coordinate = Coordinate.parse(root.pkg, Coordinate.moduleArgument(root.module) + tail(root));
        if (!coordinate.isOk()) {
            return coordinate.failure() instanceof Failure.Validation invalid
                    ? usage.error(invalid)
                    : fail(coordinate.failure(), streams, json);
        }
        QualifiedName qualified = coordinate.value().qualified();

        Failure.Validation argumentError = validate(root, coordinate.value());
        if (argumentError != null) {
            return usage.error(argumentError);
        }
        Version version = coordinate.value().version();

        // Checked before the package is ever fetched: a mistyped bucket is a fact about the argument list, not
        // about the package, and costs a round trip to Central if left until after the load.
        List<String> rest = rest(root);
        String bucket = rest.isEmpty() ? null : rest.get(0);
        boolean memberWord = bucket != null && MEMBER_WORDS.containsKey(bucket);
        if (bucket != null && !Commands.BUCKETS.contains(bucket) && !memberWord) {
            return usage.error(misnamedBucket(bucket, root, coordinate.value()));
        }

        String filter = root.filter;
        Failure.Validation extra = extraNames(bucket, rest, filter, root, qualified, version);
        if (extra != null) {
            return usage.error(extra);
        }

        // `--refresh` is only known once arguments are parsed, so the injected options are rebuilt here.
        HttpOptions resolved = http.withRefresh(root.refresh);
        Loader.LoadOptions options = new Loader.LoadOptions(
                resolved, projectDir, List.of(CentralRepository.INSTANCE), root.module, version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified, options);
        if (!loaded.isOk()) {
            return fail(loaded.failure().lengthened(tail(root)), streams, json);
        }
        if (memberWord) {
            LoadedPackage read = loaded.value();
            return fail(readAt(memberBucket(read, bucket, root, coordinate.value()), read), streams, json);
        }

        Result<DiscoverResult> answer = answer(loaded.value(), bucket, rest, filter, root.page);
        if (!answer.isOk()) {
            return fail(readAt(answer.failure(), loaded.value()), streams, json);
        }
        if (root.page != FIRST_PAGE && answer.value().paging() == null) {
            return fail(readAt(notPaged(root, coordinate.value(), filter), loaded.value()), streams, json);
        }
        String read = loaded.value().version().text();
        List<String> shown = echoed(rest);
        TextRenderer.Context where = new TextRenderer.Context(qualified.qualified(), root.module, shown,
                filter, Version.textOf(version), read);
        JsonRenderer.Where about = new JsonRenderer.Where(qualified.qualified(), read, root.module);
        streams.out().accept((json ? JsonRenderer.render(answer.value(), about)
                : TextRenderer.render(answer.value(), where)) + "\n");
        return Failure.ANSWERED;
    }

    private static int searchCentral(Commands.Root root, Streams streams, HttpOptions http, boolean json,
            UsageErrors usage) {
        List<String> keywords = rest(root);
        Failure.Validation invalid = rejectInvalidOutput(root);
        if (invalid == null) {
            invalid = SearchCentral.misuse(keywords, root.module, root.filter);
        }
        if (invalid == null) {
            invalid = pageBeforeFirst(root.page, SearchCentral.command(keywords));
        }
        if (invalid != null) {
            return usage.error(invalid);
        }
        Result<DiscoverResult> found = SearchCentral.render(keywords, root.page, http.withRefresh(root.refresh));
        if (!found.isOk()) {
            return fail(found.failure(), streams, json);
        }
        TextRenderer.Context where = new TextRenderer.Context(null, null,
                List.of(SearchCentral.VERB, String.join(" ", keywords)), null, null, null);
        streams.out().accept((json ? JsonRenderer.render(found.value())
                : TextRenderer.render(found.value(), where)) + "\n");
        return Failure.ANSWERED;
    }

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        Result<DiscoverResult> plain = answerFrom(loaded, bucket, rest, filter, page);
        if (!plain.isOk() || !loaded.bound().settles(() -> JsonRenderer.render(plain.value()))) {
            return plain;
        }
        LoadedPackage settled = loaded.withSource();
        return settled.library() == loaded.library() ? plain : answerFrom(settled, bucket, rest, filter, page);
    }

    private static Result<DiscoverResult> answerFrom(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        if (bucket == null) {
            return filter == null
                    ? Result.ok(Overview.of(loaded))
                    : Search.render(loaded, new Search.Options(List.of(filter), page));
        }
        List<String> selectors = rest.subList(1, rest.size());
        if (Search.BUCKET.equals(bucket)) {
            return Search.render(loaded, new Search.Options(selectors, page));
        }
        if (Readme.BUCKET.equals(bucket)) {
            return Readme.render(loaded, new Readme.Options(
                    selectors.isEmpty() ? null : String.join(" ", selectors), filter, page));
        }
        if (Types.BUCKET.equals(bucket)) {
            return Types.render(loaded, new Types.Options(selectors, filter, page));
        }
        Surface.Scope scope = Surface.Scope.ofVerb(bucket)
                .orElseThrow(() -> new IllegalStateException("unreachable: validated above"));
        return Containers.render(loaded, scope, new Containers.Options(selectors, filter, page));
    }

    private static Failure.Validation extraNames(String bucket, List<String> rest, String filter, Commands.Root root,
            QualifiedName qualified, Version version) {
        if (bucket == null) {
            return null;
        }
        List<String> selectors = rest.subList(1, rest.size());
        String command = new Coordinate(qualified, version).command(root.module) + " " + bucket;
        if (Types.BUCKET.equals(bucket)) {
            return Types.misuse(command, new Types.Options(selectors, filter, root.page));
        }
        if (Search.BUCKET.equals(bucket)) {
            return Search.misuse(new Coordinate(qualified, version).command(root.module) + " " + Search.BUCKET,
                    selectors, filter);
        }
        if (Surface.Scope.MODULE.verb().equals(bucket)) {
            return Containers.extraFunctionNames(command, new Containers.Options(selectors, filter, root.page));
        }
        return null;
    }

    private static String tail(List<String> rest, String filter, int page) {
        StringBuilder words = new StringBuilder();
        echoed(rest).forEach(word -> words.append(' ').append(Texts.shellWord(word)));
        if (filter != null) {
            words.append(" --filter ").append(Texts.shellWord(filter));
        }
        if (page != FIRST_PAGE) {
            words.append(" --page ").append(page);
        }
        return words.toString();
    }

    // The typed words as this tool prints them: a name or path spelled without Ballerina's escapes, as every answer
    // prints it. A readme title and a search keyword are text, not identifiers, so they are echoed as typed.
    private static List<String> echoed(List<String> rest) {
        if (rest.isEmpty() || Readme.BUCKET.equals(rest.get(0)) || Search.BUCKET.equals(rest.get(0))) {
            return rest;
        }
        return rest.stream().map(PathTree::plainSelector).toList();
    }

    private static String tail(Commands.Root root) {
        return tail(rest(root), root.filter, root.page);
    }

    private static List<String> rest(Commands.Root root) {
        return root.rest == null ? List.of() : root.rest;
    }

    private static Failure readAt(Failure failure, LoadedPackage loaded) {
        String qualified = loaded.qualified().qualified();
        String version = loaded.version().text();
        return switch (failure) {
            case Failure.Validation validation -> validation.about(qualified, version);
            case Failure.PageNotFound missing -> missing.about(qualified, version);
            default -> failure;
        };
    }

    private static Failure.Validation notPaged(Commands.Root root, Coordinate coordinate, String filter) {
        String command = coordinate.command(root.module) + tail(rest(root), filter, FIRST_PAGE);
        return new Failure.Validation(
                "This answer has only one page, so --page does not apply.",
                "Drop --page: " + Failure.quoted(command) + ".", command);
    }

    // A bucket typed where the package goes: the command with the package, when it was typed next, moved in front;
    // else the command's shape and how to find the package. Never a package the caller did not give.
    private static Failure.Validation bucketBeforePackage(Commands.Root root) {
        String word = root.pkg;
        String bucket = Commands.BUCKETS.contains(word) || MEMBER_WORDS.containsKey(word) ? word
                : BUCKET_ALIASES.get(word);
        if (bucket == null) {
            return null;
        }
        String message = "'" + word + "' is not a package; the package goes first.";
        List<String> words = rest(root);
        List<String> ends = words.isEmpty() ? List.of() : List.of(words.get(0), words.get(words.size() - 1));
        for (String typed : ends) {
            Result<Coordinate> pkg = typed.contains(PathTree.SEPARATOR) ? Coordinate.parse(typed) : null;
            if (pkg != null && pkg.isOk()) {
                List<String> others = new ArrayList<>(words);
                others.remove(typed);
                return runInstead(message, fixedCommand(root, pkg.value(), bucket, others));
            }
        }
        List<String> shape = new ArrayList<>(List.of(bucket));
        shape.addAll(words);
        String write = "Write " + Failure.quoted(Coordinate.COMMAND + PLACEHOLDER_PACKAGE
                + Coordinate.moduleArgument(root.module) + tail(shape, root.filter, root.page));
        if (words.isEmpty() || Readme.BUCKET.equals(bucket)) {
            return new Failure.Validation(message, write + "; to find the package: "
                    + Failure.quoted(Coordinate.COMMAND + SearchCentral.VERB + " " + PLACEHOLDER_KEYWORD) + ".");
        }
        String name = PathTree.plainSelector(words.get(0));
        String search = SearchCentral.command(List.of(name));
        return new Failure.Validation(message,
                write + "; to find the package that declares '" + name + "': " + Failure.quoted(search) + ".",
                search);
    }

    // A plural or another spelling of a bucket, or a word for the package itself: the command it meant. Anything else
    // is read as a name.
    private static Failure.Validation misnamedBucket(String word, Commands.Root root, Coordinate coordinate) {
        List<String> others = rest(root).subList(1, rest(root).size());
        String meant = BUCKET_ALIASES.get(word);
        if (meant != null) {
            return runInstead("'" + word + "' is not a bucket; the bucket is " + meant + ".",
                    fixedCommand(root, coordinate, meant, others));
        }
        if (PACKAGE_WORDS.contains(word)) {
            return runInstead("'" + word + "' is not a bucket; a package's overview is the package alone.",
                    fixedCommand(root, coordinate, null, others));
        }
        return unknownBucket(word, coordinate.command(root.module));
    }

    // A member word: the bucket of the container that declares what follows it, else the search for that name.
    private static Failure.Validation memberBucket(LoadedPackage loaded, String word, Commands.Root root,
            Coordinate coordinate) {
        List<String> names = rest(root).subList(1, rest(root).size());
        String member = MEMBER_WORDS.get(word);
        Surface.Scope scope = Containers.memberBucket(loaded, member.equals(A_RESOURCE_METHOD), names).orElse(null);
        if (scope != null) {
            return runInstead("'" + word + "' is not a bucket; " + member
                    + " is read under its container's bucket, here " + scope.verb() + ".",
                    fixedCommand(root, coordinate, scope.verb(), names));
        }
        String declarers = "no " + CONTAINER_BUCKETS + " in " + loaded.place() + " declares ";
        if (names.isEmpty()) {
            String overview = coordinate.command(root.module);
            return new Failure.Validation("'" + word + "' is not a bucket, and " + declarers
                    + member + ".", "See what it declares: " + Failure.quoted(overview) + ".",
                    overview);
        }
        String name = PathTree.plainSelector(names.get(0));
        String search = coordinate.command(root.module) + " " + Search.BUCKET + " " + Texts.shellWord(name);
        return new Failure.Validation("'" + word + "' is not a bucket, and " + declarers + "'" + name + "'.",
                "Search the package for it: " + Failure.quoted(search) + ".", search);
    }

    private static String fixedCommand(Commands.Root root, Coordinate coordinate, String bucket, List<String> names) {
        List<String> words = new ArrayList<>();
        if (bucket != null) {
            words.add(bucket);
        }
        words.addAll(names);
        return coordinate.command(root.module) + tail(words, root.filter, root.page);
    }

    private static Failure.Validation runInstead(String message, String command) {
        return new Failure.Validation(message, "Run " + Failure.quoted(command) + ".", command);
    }

    // A name typed where the bucket goes is most often a member's (`go doc` leaves the bucket out too): the search
    // finds whichever container declares it.
    private static Failure.Validation unknownBucket(String token, String pkg) {
        String search = pkg + " " + Search.BUCKET + " " + Texts.shellWord(token);
        return new Failure.Validation(
                "'" + token + "' is not a bucket.",
                "The buckets are " + String.join(", ", Commands.BUCKETS) + ". To find what is named '" + token
                        + "': " + Failure.quoted(search) + ".", search);
    }

    /**
     * The run's output mode: a valid {@code --output} in argv, else JSON unless stdout is a terminal. Read off argv,
     * not the parsed flag, so a parse failure is still written in the mode the caller asked for.
     */
    public static boolean jsonOutput(List<String> argv, boolean interactive) {
        return jsonOutput(argv, interactive, Commands.Grammar.create());
    }

    private static boolean jsonOutput(List<String> argv, boolean interactive, Commands.Grammar grammar) {
        Set<String> takesValue = grammar.line().getCommandSpec().options().stream()
                .filter(option -> option.arity().min() > 0)
                .flatMap(option -> Arrays.stream(option.names()))
                .collect(Collectors.toSet());
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            String value = null;
            if (token.startsWith(OUTPUT_FLAG + "=")) {
                value = token.substring(OUTPUT_FLAG.length() + 1);
            } else if (takesValue.contains(token) && i + 1 < argv.size()) {
                i++;
                value = token.equals(OUTPUT_FLAG) ? argv.get(i) : null;
            }
            if (Commands.JSON_OUTPUT.equals(value) || Commands.TEXT_OUTPUT.equals(value)) {
                return Commands.JSON_OUTPUT.equals(value);
            }
        }
        return !interactive;
    }

    private static Failure.Validation validate(Commands.Root root, Coordinate coordinate) {
        Failure.Validation output = rejectInvalidOutput(root);
        if (output != null) {
            return output;
        }
        if (root.filter != null && root.filter.isBlank()) {
            return new Failure.Validation("The --filter option needs a keyword.",
                    "Write --filter <keyword>, or drop it to list everything.");
        }
        String firstPage = coordinate.command(root.module) + tail(rest(root), root.filter, FIRST_PAGE);
        Failure.Validation page = pageBeforeFirst(root.page, firstPage);
        return page != null ? page : rejectVersionArguments(root, coordinate.qualified());
    }

    private static Failure.Validation pageBeforeFirst(int page, String firstPage) {
        if (page >= FIRST_PAGE) {
            return null;
        }
        return new Failure.Validation("Page " + page + " is out of range: pages are numbered from 1.",
                "Drop --page for the first page: " + Failure.quoted(firstPage) + ".", firstPage);
    }

    private static Failure.Validation versionFlag(Commands.Root root) {
        String message = "Unknown option '" + Commands.VERSION_FLAG + "'.";
        Result<Coordinate> coordinate = root.pkg == null ? null : Coordinate.parse(root.pkg);
        if (coordinate == null || !coordinate.isOk()) {
            return new Failure.Validation(message, "To read a specific version, write " + PLACEHOLDER_PACKAGE + ":"
                    + PLACEHOLDER_VERSION);
        }
        return writtenVersion(message, root, coordinate.value().qualified(), root.version, tail(root));
    }

    private static Failure.Validation writtenVersion(String message, Commands.Root root, QualifiedName qualified,
            String version, String tail) {
        if (!Version.isComplete(version)) {
            return new Failure.Validation(message, WRITE_VERSION + qualified.qualified() + ":" + PLACEHOLDER_VERSION);
        }
        String command = new Coordinate(qualified, Version.parse(version).value()).command(root.module) + tail;
        return new Failure.Validation(message, WRITE_VERSION + Failure.quoted(command) + ".", command);
    }

    private static Failure.Validation rejectInvalidOutput(Commands.Root root) {
        if (root.output == null || Commands.JSON_OUTPUT.equals(root.output)
                || Commands.TEXT_OUTPUT.equals(root.output)) {
            return null;
        }
        return new Failure.Validation(
                "'" + root.output + "' is not a valid --output value.",
                "Pass --output " + Commands.JSON_OUTPUT + " or --output " + Commands.TEXT_OUTPUT + ".");
    }

    private static Failure.Validation rejectVersionArguments(Commands.Root root, QualifiedName qualified) {
        List<String> tokens = root.rest;
        if (tokens == null) {
            return null;
        }
        String misplaced = tokens.stream()
                .filter(Version::isComplete)
                .findFirst()
                .orElse(null);
        if (misplaced == null) {
            return null;
        }
        List<String> others = new ArrayList<>(tokens);
        others.remove(misplaced);
        return writtenVersion("'" + misplaced + "' looks like a version.", root, qualified, misplaced,
                tail(others, root.filter, root.page));
    }

    // The failure a parse error is, or null when picocli's own first line says it best.
    private static Failure.Validation describeParseError(CommandLine.ParameterException cause,
            Commands.Grammar grammar) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            // Every positional fits `[bucket] [name ...]`, so only an option is ever left unmatched.
            return new Failure.Validation(
                    "Unknown option '" + (tokens.isEmpty() ? "" : tokens.get(0)) + "'.",
                    UsageRenderer.knownFlags(grammar));
        }
        if (cause instanceof CommandLine.OverwrittenOptionException overwritten
                && overwritten.getOverwritten() instanceof CommandLine.Model.OptionSpec option) {
            return new Failure.Validation(
                    option.longestName() + " is given more than once.", "Pass " + option.longestName() + " once.");
        }
        if (cause instanceof CommandLine.MissingParameterException missing) {
            return missing.getMissing().stream()
                    .filter(CommandLine.Model.OptionSpec.class::isInstance)
                    .map(CommandLine.Model.OptionSpec.class::cast)
                    .findFirst()
                    .map(Cli::missingValue)
                    .orElse(null);
        }
        if (cause.getArgSpec() instanceof CommandLine.Model.OptionSpec option && cause.getValue() != null) {
            if (option.type() == boolean.class) {
                return new Failure.Validation(
                        option.longestName() + " takes no value.",
                        "Write " + option.longestName() + ".");
            }
            return new Failure.Validation(
                    "'" + cause.getValue() + "' is not a valid " + option.longestName() + " value.",
                    "Write " + option.longestName() + " " + option.paramLabel() + ".");
        }
        return null;
    }

    private static Failure.Validation missingValue(CommandLine.Model.OptionSpec option) {
        String name = option.longestName();
        String label = option.paramLabel();
        return new Failure.Validation(
                "The " + name + " option needs a value.",
                "Write " + name + " " + label + " or " + name + "=" + label + ".");
    }

    private static String firstLine(String message) {
        String line = message == null ? "Invalid arguments" : message.split("\n", -1)[0];
        return line.endsWith(".") ? line : line + ".";
    }

    private static int fail(Failure failure, Streams streams, boolean json) {
        streams.errorOut().accept(failure.describe(json) + "\n");
        return failure.exitCode();
    }

    private record UsageErrors(Commands.Grammar grammar, Streams streams, boolean json) {

        int error(Failure.Validation failure) {
            return write(failure, true);
        }

        int helpError(String message) {
            return write(new Failure.Validation(message, RUN_HELP), false);
        }

        private int write(Failure.Validation failure, boolean pointToHelp) {
            String usage = json ? "" : "\n" + UsageRenderer.usageLine(grammar)
                    + (pointToHelp ? "\nRun `" + HELP + "` for details." : "");
            streams.errorOut().accept(failure.describe(json) + usage + "\n");
            return failure.exitCode();
        }
    }
}
