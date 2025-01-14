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

import React from 'react';
import classNames from 'classnames';
import {Icon} from 'antd';
import {isObservableArray} from 'mobx';
import {PreviewIcons} from '../../preview/previewIcons';
import SearchItemTypes from '../../../../models/search/search-item-types';
import getDocumentName from '../document-presentation/utilities/get-document-name';
import UserName from '../../../special/UserName';
import displaySize from '../../../../utils/displaySize';
import displayDate from '../../../../utils/displayDate';
import styles from '../search-results.css';
import OpenInToolAction from '../../../special/file-actions/open-in-tool';
import {SearchGroupTypes} from '../../searchGroupTypes';

function parseExtraColumns (preferences) {
  const configuration = preferences.searchExtraFieldsConfiguration;
  if (configuration) {
    if (Array.isArray(configuration) || isObservableArray(configuration)) {
      return configuration.map(field => ({
        key: field,
        name: field
      }));
    } else {
      const result = [];
      const types = Object.keys(configuration);
      for (let t = 0; t < types.length; t++) {
        const type = types[t];
        const subConfiguration = configuration[type];
        if (Array.isArray(subConfiguration) || isObservableArray(subConfiguration)) {
          for (let f = 0; f < subConfiguration.length; f++) {
            const field = subConfiguration[f];
            let item = result.find(r => r.key === field);
            if (!item) {
              item = {
                key: field,
                name: field,
                types: new Set()
              };
              result.push(item);
            }
            item.types.add(type);
          }
        }
      }
      return result;
    }
  } else {
    return [];
  }
}

function fetchAndParseExtraColumns (preferences) {
  return new Promise((resolve) => {
    preferences
      .fetchIfNeededOrWait()
      .then(() => resolve(parseExtraColumns(preferences)))
      .catch(() => resolve([]));
  });
}

const renderIcon = (resultItem) => {
  if (PreviewIcons[resultItem.type]) {
    return (
      <Icon
        className={classNames('cp-icon-larger', styles.icon, 'cp-search-result-item-main')}
        type={PreviewIcons[resultItem.type]} />
    );
  }
  return null;
};

const Name = {
  key: 'name',
  name: 'Name',
  renderFn: (value, document, onClick, renderCheckBox) => (
    <div
      style={{
        display: 'flex',
        width: '100%',
        height: '100%',
        alignItems: 'center',
        flexWrap: 'nowrap'
      }}
    >
      <div className="cp-search-result-item-actions">
        <Icon
          type="info-circle-o"
          className={
            classNames(
              'cp-search-result-item-action',
              'cp-icon-larger',
              'cp-search-result-item-main'
            )
          }
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            onClick && onClick(document);
          }}
        />
        {renderCheckBox && renderCheckBox(document)}
        <OpenInToolAction
          file={document.path}
          storageId={document.parentId}
          className={
            classNames(
              'cp-search-result-item-action',
              'cp-icon-larger'
            )
          }
          titleStyle={{height: '1em'}}
        />
      </div>
      {renderIcon(document)}
      <span
        className={
          classNames(
            'cp-ellipsis-text',
            'cp-search-result-item-main'
          )
        }
      >
        <b>
          {getDocumentName(document)}
        </b>
      </span>
    </div>
  ),
  width: '25%'
};

const Owner = {
  key: 'owner',
  name: 'Owner',
  width: '15%',
  renderFn: (value) => (
    <UserName
      userName={value}
      style={{
        whiteSpace: 'nowrap',
        overflow: 'hidden',
        textOverflow: 'ellipsis'
      }}
    />
  )
};

const Description = {
  key: 'description',
  name: 'Description',
  width: '15%',
  types: new Set([
    SearchItemTypes.pipeline,
    SearchItemTypes.tool,
    SearchItemTypes.toolGroup,
    SearchItemTypes.configuration,
    SearchItemTypes.issue
  ])
};

const Changed = {
  key: 'lastModified',
  name: 'Changed',
  width: '15%',
  renderFn: value => (
    <span className="cp-ellipsis-text">
      {displayDate(value, 'MMM D, YYYY, HH:mm')}
    </span>
  ),
  types: new Set([
    SearchItemTypes.s3File,
    SearchItemTypes.gsFile,
    SearchItemTypes.azFile,
    SearchItemTypes.NFSFile
  ])
};

const Path = {
  key: 'path',
  name: 'Path',
  width: '25%',
  types: new Set([
    SearchItemTypes.tool,
    SearchItemTypes.s3File,
    SearchItemTypes.gsFile,
    SearchItemTypes.azFile,
    SearchItemTypes.NFSFile,
    SearchItemTypes.NFSBucket,
    SearchItemTypes.azStorage,
    SearchItemTypes.gsStorage,
    SearchItemTypes.s3Bucket
  ])
};

const Size = {
  key: 'size',
  name: 'Size',
  width: '15%',
  renderFn: value => (
    <span className="cp-ellipsis-text">
      {displaySize(value, false)}
    </span>
  ),
  types: new Set([
    SearchItemTypes.s3File,
    SearchItemTypes.gsFile,
    SearchItemTypes.azFile,
    SearchItemTypes.NFSFile
  ])
};

const Started = {
  key: 'startDate',
  name: 'Started',
  width: '15%',
  renderFn: value => (
    <span className="cp-ellipsis-text">
      {displayDate(value, 'MMM D, YYYY, HH:mm')}
    </span>
  ),
  types: new Set([SearchItemTypes.run])
};

const Finished = {
  key: 'endDate',
  name: 'Finished',
  width: '15%',
  renderFn: value => (
    <span className="cp-ellipsis-text">
      {displayDate(value, 'MMM D, YYYY, HH:mm')}
    </span>
  ),
  types: new Set([SearchItemTypes.run])
};

const CloudPath = {
  key: 'cloud_path',
  name: 'Cloud path',
  width: '15%',
  types: new Set([
    SearchItemTypes.s3File,
    SearchItemTypes.NFSFile,
    SearchItemTypes.gsFile,
    SearchItemTypes.azFile
  ])
};

const MountPath = {
  key: 'mount_path',
  name: 'Mount path',
  width: '15%',
  types: new Set([
    SearchItemTypes.s3File,
    SearchItemTypes.NFSFile,
    SearchItemTypes.gsFile,
    SearchItemTypes.azFile
  ])
};

const DocumentColumns = [
  Name,
  Owner,
  Description,
  Changed,
  Path,
  CloudPath,
  MountPath,
  Size,
  Started,
  Finished
];

const mainColumns = [Name, Description, Changed, Size];
const detailsColumns = [Owner, Path];
const otherColumns = DocumentColumns
  .filter((aColumn) => !mainColumns.includes(aColumn) && !detailsColumns.includes(aColumn));

/**
 * @typedef {Object} ColumnConfig
 * @property {string} key
 * @property {string} name
 */

/**
 * @typedef {string|ColumnConfig} Column
 */

/**
 * @typedef {Object} ColumnsOptions
 * @property {ColumnConfig[]} facetsColumns
 * @property {ColumnConfig[]} extraColumns
 * @property {Column[]} searchColumnsOrder
 */

/**
 * @param {string} group
 * @param {ColumnsOptions} options
 * @returns {ColumnConfig[]}
 */
function getColumnsFromGroup (group, options = {}) {
  if (!group) {
    return [];
  }
  const {
    extraColumns = [],
    facetsColumns = []
  } = options || {};
  switch (group.toLowerCase()) {
    case 'main':
      return mainColumns.slice();
    case 'extra':
      return extraColumns.slice();
    case 'facets':
      return facetsColumns.slice();
    case 'details':
      return detailsColumns.slice();
    case 'other':
      return otherColumns.slice();
    default:
      return [];
  }
}

/**
 * @param {Column} config
 * @param {ColumnsOptions} [options]
 * @returns {{columns: ColumnConfig[], group: boolean}}
 */
function getColumns (config, options = {}) {
  if (typeof config === 'string' && /^<.+>$/.test(config)) {
    const groupName = config.slice(1, -1);
    return {
      columns: getColumnsFromGroup(groupName, options),
      group: true
    };
  }
  if (typeof config === 'string') {
    const findColumnByProperty = (propertyName) => DocumentColumns
      .find((aColumn) => (aColumn[propertyName] || '').toLowerCase() === config.toLowerCase());
    const aColumn = findColumnByProperty('key') || findColumnByProperty('name');
    return {
      columns: aColumn ? [aColumn] : [{key: config, name: config}]
    };
  }
  if (typeof config === 'object' && typeof config.key === 'string') {
    return {
      columns: [{
        key: config.key,
        name: config.name || config.key
      }]
    };
  }
  return {
    columns: []
  };
}

const DEFAULT_ORDER = ['<MAIN>', '<FACETS>', '<EXTRA>', '<DETAILS>', '<OTHER>'];

/**
 * @param {ColumnsOptions} options
 * @returns {ColumnConfig[]}
 */
function getDefaultColumns (
  options = {}
) {
  const {
    searchColumnsOrder = DEFAULT_ORDER
  } = options || {};
  const order = searchColumnsOrder && searchColumnsOrder.length
    ? searchColumnsOrder
    : DEFAULT_ORDER;
  const result = [];
  const parsed = order.map((aConfig) => getColumns(aConfig, options));
  const explicitColumns = parsed
    .filter((parsedConfig) => !parsedConfig.group)
    .reduce((result, config) => ([...result, ...config.columns]), []);
  const columnDefinedExplicitly = (aColumn) =>
    explicitColumns.some((used) => used.key === aColumn.key);
  const columnAdded = (aColumn) =>
    result.some((item) => item.key === aColumn.key);
  for (let p = 0; p < parsed.length; p++) {
    const {
      columns = [],
      group = false
    } = parsed[p];
    const items = group
      ? columns.filter((aColumn) => !columnDefinedExplicitly(aColumn))
      : columns;
    for (let i = 0; i < items.length; i++) {
      const column = items[i];
      if (!columnAdded(column)) {
        result.push(column);
      }
    }
  }
  return result;
}

function filterDisplayedColumns (columns = [], documentTypes = []) {
  let shouldExcludePathColumn = false;
  if (documentTypes.length > 0) {
    shouldExcludePathColumn = !documentTypes
      .some((type) => !SearchGroupTypes.storage.types.includes(type));
  }
  return columns
    .filter((column) => !shouldExcludePathColumn || column !== Path);
}

export {
  DocumentColumns,
  fetchAndParseExtraColumns,
  getDefaultColumns,
  filterDisplayedColumns,
  parseExtraColumns,
  Name,
  Changed,
  Size,
  Owner,
  Path,
  CloudPath,
  MountPath,
  Description,
  Started,
  Finished
};
