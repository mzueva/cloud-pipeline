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

import React from 'react';
import {observer} from 'mobx-react';
import PropTypes from 'prop-types';
import classNames from 'classnames';
import {
  Button,
  Row,
  Checkbox,
  Icon,
  Popover
} from 'antd';
import {
  SortableContainer,
  SortableElement,
  SortableHandle,
  arrayMove
} from 'react-sortable-hoc';
import styles from './DropdownWithMultiselect.css';

@observer
export default class DropdownWithMultiselect extends React.Component {
  static propTypes = {
    className: PropTypes.string,
    onColumnSelect: PropTypes.func,
    onSetOrder: PropTypes.func,
    onResetColumns: PropTypes.func,
    columns: PropTypes.array,
    columnNameFn: PropTypes.func,
    size: PropTypes.oneOf(['small', 'large', 'default']),
    style: PropTypes.object
  };

  state = {
    isOpenMenu: false,
    selectedColumns: [],
    columns: []
  };

  itemIsSelected = (item) => {
    return this.state.selectedColumns.filter(({key}) => key === item).length === 1;
  };

  onColumnSelect = (column) => () => {
    if (this.props.onColumnSelect) {
      this.props.onColumnSelect(column);
    }
  };

  openMenu = () => {
    this.setState({isOpenMenu: !this.state.isOpenMenu});
  };

  onSortEnd = ({oldIndex, newIndex}) => {
    const columns = arrayMove(this.state.columns, oldIndex, newIndex);
    this.setState({columns}, () => {
      if (this.props.onSetOrder) {
        this.props.onSetOrder(columns);
      }
    });
  };

  onResetColumns = () => {
    if (this.props.onResetColumns) {
      this.props.onResetColumns();
    }
  };

  renderColumnsMenu = () => {
    let {columnNameFn} = this.props;
    if (!columnNameFn) {
      columnNameFn = (o) => o;
    }
    const columns = this.state.columns;
    const DragHandle = SortableHandle(() => <span><Icon type="bars" /></span>);
    const SortableItem = SortableElement(({value}) => {
      return (
        <Row className={classNames(styles.row, 'cp-metadata-dropdown-row')}>
          <DragHandle />
          <Checkbox
            disabled={this.state.selectedColumns.length <= 1 && this.itemIsSelected(value)}
            checked={this.itemIsSelected(value)}
            onChange={this.onColumnSelect(value)}>
            {columnNameFn(value)}
          </Checkbox>
        </Row>
      );
    });

    const SortableList = SortableContainer(({items}) => {
      return (
        <div style={{margin: '-2px -10px', width: 250}}>
          {items.map(({key}, index) => (
            <SortableItem key={`item-${index}`} index={index} value={key} />
          ))}
        </div>
      );
    });
    return (
      <div>
        <Button
          style={{width: '100%', marginBottom: '5px'}}
          onClick={this.onResetColumns}>
          Reset Columns
        </Button>
        <SortableList
          items={columns}
          onSortEnd={this.onSortEnd}
          useDragHandle
        />
      </div>
    );
  };

  render () {
    const {
      className,
      style,
      size
    } = this.props;
    return (
      <Popover
        trigger="click"
        title="Show columns"
        placement="bottomRight"
        content={this.renderColumnsMenu()}>
        <Button
          id="metadata-manage-columns-button"
          className={className}
          style={Object.assign({lineHeight: 1}, style || {})}
          onClick={this.openMenu}
          size={size}
        >
          <Icon type="bars" />
        </Button>
      </Popover>
    );
  }

  componentWillReceiveProps (props) {
    this.setState({
      selectedColumns: props.columns.filter(c => c.selected),
      columns: props.columns
    });
  }
}
