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

import React from 'react';
import PropTypes from 'prop-types';
import classNames from 'classnames';
import {inject, observer} from 'mobx-react';
import {
  Checkbox,
  Icon,
  Input,
  Row
} from 'antd';
import {renderPipelineName} from './utilities';
import {
  getFiltersState,
  onFilterDropdownVisibilityChangedGenerator,
  onFilterGenerator
} from './state-utilities';
import RunLoadingPlaceholder from './run-loading-placeholder';
import styles from './run-table-columns.css';

const MINIMUM_SEARCH_LENGTH = 3;

function PipelinesFilterComponent (
  {
    pipelines: pipelinesRequest,
    search,
    onSearch,
    value,
    onChange,
    onOk,
    onClear
  }
) {
  if (!pipelinesRequest) {
    return null;
  }
  const {
    pending,
    value: pipelinesValue = [],
    loaded
  } = pipelinesRequest;
  if (pending && !loaded) {
    return (
      <div
        className={
          classNames(
            styles.filterPopoverContainer,
            'cp-filter-popover-container'
          )
        }
      >
        <Icon type="loading" />
      </div>
    );
  }
  const enabled = new Set((value || []).map((id) => Number(id)));
  const pipelines = pipelinesValue
    .filter((pipeline) => enabled.has(Number(pipeline.id)) || (
      search &&
      search.length >= MINIMUM_SEARCH_LENGTH &&
      (pipeline.name || '').toLowerCase().includes(search.toLowerCase())
    ))
    .sort((a, b) => (a.name || '').localeCompare(b.name || ''));
  const onChangeSelection = (pipelineId) => (event) => {
    if (event.target.checked && !enabled.has(Number(pipelineId))) {
      onChange([...enabled, Number(pipelineId)]);
    } else if (!event.target.checked && enabled.has(Number(pipelineId))) {
      onChange([...enabled].filter((s) => s !== Number(pipelineId)));
    }
  };
  const onSearchChanged = (event) => {
    if (typeof onSearch === 'function') {
      onSearch(event.target.value);
    }
  };
  return (
    <div
      className={
        classNames(
          styles.filterPopoverContainer,
          'cp-filter-popover-container'
        )
      }
      style={{minWidth: 250}}
    >
      <Row>
        <Input.Search
          placeholder="Filter pipelines"
          value={search}
          onChange={onSearchChanged}
        />
      </Row>
      <Row>
        <div style={{maxHeight: 400, overflowY: 'auto'}}>
          {
            pipelines
              .map((pipeline) => (
                <Row
                  style={{margin: 5}}
                  key={pipeline.id}
                >
                  <Checkbox
                    onChange={onChangeSelection(pipeline.id)}
                    checked={enabled.has(Number(pipeline.id))}
                  >
                    {pipeline.name}
                  </Checkbox>
                </Row>
              ))
          }
        </div>
      </Row>
      <Row
        type="flex"
        justify="space-between"
        className={styles.filterActionsButtonsContainer}
      >
        <a onClick={onOk}>OK</a>
        <a onClick={onClear}>Clear</a>
      </Row>
    </div>
  );
}

PipelinesFilterComponent.propTypes = {
  search: PropTypes.string,
  onSearch: PropTypes.func,
  value: PropTypes.oneOfType([PropTypes.object, PropTypes.array]),
  onChange: PropTypes.func,
  onOk: PropTypes.func,
  onClear: PropTypes.func
};

const PipelinesFilter = inject('pipelines')(observer(PipelinesFilterComponent));

PipelinesFilter.propTypes = {
  search: PropTypes.string,
  onSearch: PropTypes.func,
  value: PropTypes.oneOfType([PropTypes.object, PropTypes.array]),
  onChange: PropTypes.func,
  onOk: PropTypes.func,
  onClear: PropTypes.func
};

function getColumnFilter (state, setState) {
  const parameter = 'pipelineIds';
  const onFilterDropdownVisibleChange = onFilterDropdownVisibilityChangedGenerator(
    parameter,
    state,
    setState
  );
  const {
    value,
    visible: filterDropdownVisible,
    onChange,
    filtered,
    search,
    onSearch
  } = getFiltersState(parameter, state, setState);
  const onFilter = onFilterGenerator(parameter, state, setState);
  const clear = () => onFilter(undefined);
  const onOk = () => onFilter(value);
  return {
    filterDropdown: (
      <PipelinesFilter
        search={search}
        onSearch={onSearch}
        value={value}
        onChange={onChange}
        onOk={onOk}
        onClear={clear}
      />
    ),
    filterDropdownVisible,
    filtered,
    onFilterDropdownVisibleChange
  };
}

const getColumn = (localizedString) => ({
  title: localizedString('Pipeline'),
  dataIndex: 'pipelineName',
  key: 'pipelineIds',
  className: styles.runRowPipeline,
  render: (name, run) => (
    <RunLoadingPlaceholder run={run} empty>
      {renderPipelineName(run)}
    </RunLoadingPlaceholder>
  )
});

export {
  getColumn,
  getColumnFilter
};
