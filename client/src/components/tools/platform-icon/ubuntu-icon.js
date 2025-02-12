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

/* eslint-disable max-len */
import React from 'react';
import classNames from 'classnames';
import {PUBLIC_URL} from '../../../config';
import styles from './tool-icons.css';

export default function UbuntuIcon ({className, style}) {
  return (
    <img
      className={
        classNames(
          styles.toolIcon,
          styles.linux,
          className
        )
      }
      style={style}
      src={`${PUBLIC_URL || ''}/ubuntu.svg`}
    />
  );
}
