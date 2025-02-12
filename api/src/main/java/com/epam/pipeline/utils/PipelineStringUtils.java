/*
 * Copyright 2017-2019 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PipelineStringUtils {

    public static final String DASH = "-";
    private static final String ALPHANUMERIC_DASH_TEMPLATE = "[^a-zA-Z0-9\\-]+";

    private PipelineStringUtils() {
        //no op
    }

    public static String convertToAlphanumericWithDashes(final String input) {
        if (StringUtils.isBlank(input)) {
            return input;
        }
        return input.replaceAll(ALPHANUMERIC_DASH_TEMPLATE, DASH);
    }

    public static Set<String> parseCommaSeparatedSet(final Optional<String> input) {
        return input.filter(StringUtils::isNotBlank)
                .map(value -> value.split(","))
                .map(Arrays::stream)
                .orElseGet(Stream::empty)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }
}
