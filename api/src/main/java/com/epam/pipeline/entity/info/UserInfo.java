/*
 * Copyright 2017-2020 EPAM Systems, Inc. (https://www.epam.com/)
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

package com.epam.pipeline.entity.info;

import com.epam.pipeline.entity.user.PipelineUser;
import com.epam.pipeline.entity.user.Role;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@NoArgsConstructor(force = true)
public class UserInfo {

    private final Long id;
    private final String name;
    private final Map<String, String> attributes;
    private final List<Role> roles;
    private final List<String> groups;

    public UserInfo(final PipelineUser user) {
        this.attributes = user.getAttributes();
        this.id = user.getId();
        this.name = user.getUserName();
        this.roles = user.getRoles();
        this.groups = user.getGroups();
    }
}
