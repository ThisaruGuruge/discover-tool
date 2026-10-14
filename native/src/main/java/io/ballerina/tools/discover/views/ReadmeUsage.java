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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Identifiers;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often a readme uses each name, so an overview section shows the rows a reader meets first.
 *
 * <p>A use is one whole identifier in the readme's code (fenced blocks and inline code spans; prose words never
 * count, as {@code Status} in "Online Certificate Status Protocol" would), compared case-sensitively by its plain
 * spelling: {@code 'public} and {@code public} are one name, {@code http:Client} uses {@code Client}, and
 * {@code ClientConfiguration} does not.
 *
 * @since 0.1.0
 */
final class ReadmeUsage {

    private static final String ESCAPED = "\\\\[^\\w\\s]";
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?<![\\w\\\\'])'?(?:[A-Za-z_]|" + ESCAPED + ")(?:\\w|" + ESCAPED + ")*");

    private ReadmeUsage() {
    }

    /** How many times {@code code} uses each name it uses at all. */
    static Map<String, Integer> counts(String code) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = IDENTIFIER.matcher(code);
        while (matcher.find()) {
            counts.merge(Identifiers.plain(matcher.group()), 1, Integer::sum);
        }
        return counts;
    }

    /** {@code rows} most used first; a tie, or a name the readme never uses, keeps its place in {@code rows}. */
    static List<DiscoverResult.Overview.Row> ranked(List<DiscoverResult.Overview.Row> rows, Optional<String> readme) {
        if (readme.isEmpty()) {
            return rows;
        }
        Map<String, Integer> counts = counts(Readme.codeOf(readme.get()));
        return rows.stream()
                .sorted(Comparator.comparingInt(
                        (DiscoverResult.Overview.Row row) -> counts.getOrDefault(Identifiers.plain(row.name()), 0))
                        .reversed())
                .toList();
    }
}
