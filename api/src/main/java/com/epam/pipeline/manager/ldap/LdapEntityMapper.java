/*
 * Copyright 2021 EPAM Systems, Inc. (https://www.epam.com/)
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

package com.epam.pipeline.manager.ldap;

import com.epam.pipeline.entity.ldap.LdapEntity;
import com.epam.pipeline.entity.ldap.LdapEntityType;
import com.epam.pipeline.exception.ldap.LdapException;
import com.epam.pipeline.manager.preference.PreferenceManager;
import com.epam.pipeline.manager.preference.SystemPreferences;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class LdapEntityMapper {
    
    private static final String FALLBACK_ENTITY_NAME_ATTRIBUTE = "cn";
    
    private final PreferenceManager preferenceManager;

    public LdapEntity map(final Attributes attributes, final LdapEntityType type, final String nameAttribute) {
        final Map<String, List<String>> attributesMap = resolveAttributes(attributes);
        return new LdapEntity(nameFrom(attributesMap, nameAttribute), type, attributesMap);
    }

    private Map<String, List<String>> resolveAttributes(final Attributes attributes) {
        return Collections.list(attributes.getIDs())
                .stream()
                .map(attributes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Attribute::getID, this::toValues));
    }

    private List<String> toValues(final Attribute attribute) {
        try {
            return Collections.list(attribute.getAll()).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        } catch (NamingException e) {
            throw new LdapException("Attribute values extraction has failed", e);
        }
    }

    private String nameFrom(final Map<String, List<String>> attributeValues, final String nameAttribute) {
        return Optional.of(nameAttribute(nameAttribute))
                .map(attributeValues::get)
                .map(List::stream)
                .map(Stream::findFirst)
                .flatMap(Function.identity())
                .orElse("Name not found");
    }

    private String nameAttribute(final String nameAttribute) {
        if (StringUtils.isNotBlank(nameAttribute)) {
            return nameAttribute;
        }
        return preferenceManager.findPreference(SystemPreferences.LDAP_NAME_ATTRIBUTE)
                .orElse(FALLBACK_ENTITY_NAME_ATTRIBUTE);
    }
}
