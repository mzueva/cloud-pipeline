/*
 * Copyright 2017-2022 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import PipelineRunSSH from './PipelineRunSSH';

class PipelineRunSSHCache {
  static getCacheValue (cache, id) {
    if (!cache.has(+id)) {
      cache.set(+id, new PipelineRunSSH(id));
    }
    return cache.get(+id);
  }

  cache = new Map();

  getPipelineRunSSH = (id) => {
    return this.constructor.getCacheValue(this.cache, id);
  };
}

const pipelineRunSSHCache = new PipelineRunSSHCache();

export default pipelineRunSSHCache;
