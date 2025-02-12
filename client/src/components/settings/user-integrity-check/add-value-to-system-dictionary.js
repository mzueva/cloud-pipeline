/*
 * Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
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

import SystemDictionariesUpdate from '../../../models/systemDictionaries/SystemDictionariesUpdate';

function addValueToSystemDictionary (
  dictionary,
  values = []
) {
  const request = new SystemDictionariesUpdate();
  if (!dictionary) {
    return Promise.resolve();
  }
  const {
    id: currentId,
    key: currentKey,
    values: currentValues = []
  } = dictionary;
  return new Promise((resolve, reject) => {
    request.send({
      id: currentId,
      key: currentKey,
      values: [
        ...currentValues,
        ...values.map(value => ({
          autofill: true,
          value
        }))
      ]
    })
      .then(() => {
        if (request.error || !request.loaded) {
          reject(new Error(request.error || `Error updating dictionary ${currentKey}`));
        } else {
          resolve();
        }
      })
      .catch(reject);
  });
}

export default addValueToSystemDictionary;
