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
import PropTypes from 'prop-types';
import classNames from 'classnames';
import {ChromePicker} from 'react-color';
import {
  Button,
  Checkbox,
  Icon,
  InputNumber,
  Popover,
  Select
} from 'antd';
import {
  buildColor,
  parseAmount
} from '../../../../themes/utilities/color-utilities';
import {VariableNames} from '../utilities/variable-descriptions';
import styles from './color-variable.css';

const types = {
  color: '__color__',
  variable: '__variable__',
  function: '__function__'
};

const functions = {
  fade: 'fade',
  fadein: 'fadein',
  fadeout: 'fadeout',
  lighten: 'lighten',
  darken: 'darken'
};

const FunctionNames = {
  [functions.fade]: 'Set opacity',
  [functions.fadein]: 'Increase opacity',
  [functions.fadeout]: 'Decrease opacity',
  [functions.lighten]: 'Lighten color',
  [functions.darken]: 'Darken color'
};

const parse = (value) => {
  if (value) {
    const e = /^(fade|fadein|fadeout|lighten|darken)\(\s*(.+)\s*,\s*(.+)\)/i.exec(value);
    if (e) {
      return {
        type: types.function,
        value: e[2],
        function: e[1],
        amount: parseAmount(e[3]),
        key: e[2]
      };
    }
    if (/^@/.test(value)) {
      return {type: types.variable, value, key: value};
    }
  }
  return {type: types.color, value, key: types.color};
};

function ColorPresenter (
  {
    className,
    color,
    onClick,
    borderless,
    style
  }
) {
  if (!color) {
    return null;
  }
  return (
    <div
      className={
        classNames(
          className,
          styles.colorPresenter,
          'color-presenter',
          {
            [styles.borderless]: borderless,
            borderless
          }
        )
      }
      style={
        Object.assign(
          {},
          style || {},
          {backgroundColor: color}
        )
      }
      onClick={onClick}
    >
      {'\u00A0'}
    </div>
  );
}

class ColorPicker extends React.Component {
  state = {
    visible: false
  };

  onVisibilityChange = (visible) => {
    this.setState({
      visible
    });
  };

  onChange = (e) => {
    const {
      rgb
    } = e;
    const {
      onChange
    } = this.props;
    if (onChange) {
      onChange(buildColor(rgb));
    }
  }

  render () {
    const {color, disabled} = this.props;
    if (disabled) {
      return (
        <ColorPresenter
          className={styles.colorPicker}
          color={color}
          style={{cursor: 'default'}}
        />
      );
    }
    return (
      <Popover
        onVisibleChange={this.onVisibilityChange}
        trigger={['click']}
        content={(
          <ChromePicker
            color={color}
            onChangeComplete={this.onChange}
          />
        )}
      >
        <ColorPresenter
          className={styles.colorPicker}
          onClick={() => this.onVisibilityChange(true)}
          color={color}
        />
      </Popover>
    );
  }
}

ColorPicker.propTypes = {
  color: PropTypes.string,
  onChange: PropTypes.func,
  disabled: PropTypes.bool
};

class ColorVariable extends React.PureComponent {
  componentDidMount () {
    this.updateFromProps();
  }

  componentDidUpdate (prevProps, prevState, snapshot) {
    if (prevProps.value !== this.props.value) {
      this.updateFromProps();
    }
  }

  updateFromProps = () => {

  };

  onChangeType = (newType) => {
    const {
      variable,
      onChange,
      parsedValue,
      value
    } = this.props;
    if (!onChange) {
      return;
    }
    if (!newType) {
      onChange(variable, parsedValue || '#000000');
    } else {
      // variable
      const {
        type,
        function: f,
        amount
      } = parse(value);
      if (type === types.function) {
        onChange(variable, `${f}(${newType},${Math.round(amount * 100)}%)`);
      } else {
        onChange(variable, newType);
      }
    }
  };

  renderAsColor = () => {
    const {
      parsedValue,
      disabled,
      variable,
      onChange
    } = this.props;
    const onChangeColor = (color) => {
      if (onChange) {
        onChange(variable, color);
      }
    };
    return (
      <ColorPicker
        color={parsedValue}
        onChange={onChangeColor}
        disabled={disabled}
      />
    );
  };

  renderFunction = () => {
    const {
      value,
      variable,
      onChange,
      error,
      disabled
    } = this.props;
    const {
      function: f,
      amount,
      value: arg
    } = parse(value);
    const onChangeFunction = (e) => {
      if (!onChange) {
        return;
      }
      if (!e) {
        onChange(variable, arg);
      } else {
        let defaultAmount = amount;
        if (defaultAmount === undefined) {
          switch (e) {
            case functions.fadein:
            case functions.fadeout:
            case functions.lighten:
            case functions.darken:
              defaultAmount = 0.1;
              break;
          }
        }
        onChange(variable, `${e}(${arg},${Math.round(defaultAmount * 100)}%)`);
      }
    };
    const onChangeAmount = (e) => {
      if (!onChange || !f) {
        return;
      }
      onChange(variable, `${f}(${arg},${e}%)`);
    };
    return [
      <Select
        allowClear
        disabled={disabled}
        className={
          classNames(
            {
              'cp-error': error
            }
          )
        }
        placeholder="Set additional parameters..."
        key="function"
        value={f}
        style={{
          width: 175
        }}
        onChange={onChangeFunction}
      >
        {
          Object.values(functions).map(func => (
            <Select.Option key={func} value={func}>
              {FunctionNames[func]}
            </Select.Option>
          ))
        }
      </Select>,
      f && (
        <InputNumber
          disabled={disabled}
          className={
            classNames(
              {
                'cp-error': error
              }
            )
          }
          key="amount"
          value={Math.round(amount * 100)}
          min={0}
          max={100}
          step={1}
          onChange={onChangeAmount}
          style={{
            width: 60
          }}
        />
      ),
      f && (
        <span
          key="amount addon"
        >
          %
        </span>
      )
    ].filter(Boolean);
  };

  onClear = () => {
    const {onChange, variable} = this.props;
    if (onChange) {
      onChange(variable, undefined);
    }
  }

  onRevert = () => {
    const {
      onChange,
      variable,
      initialValue
    } = this.props;
    if (onChange) {
      onChange(variable, initialValue);
    }
  }

  render () {
    const {
      className,
      value,
      modifiedValue,
      initialValue,
      variables = [],
      parsedValues = {},
      extended,
      error,
      disabled
    } = this.props;
    const {
      key,
      type
    } = parse(value);
    return (
      <div
        className={
          classNames(
            styles.colorVariable,
            className
          )
        }
      >
        {this.renderAsColor()}
        <Select
          allowClear
          showSearch
          disabled={disabled}
          className={
            classNames(
              {
                'cp-error': error
              }
            )
          }
          placeholder="Pick color from property..."
          value={key === types.color ? undefined : key}
          style={{width: 250}}
          onChange={this.onChangeType}
          filterOption={
            (input, option) => {
              const value = VariableNames[option.key] || option.key;
              return (value.toLowerCase().indexOf(input.toLowerCase()) >= 0);
            }
          }
        >
          {
            variables.map(variable => (
              <Select.Option
                key={variable}
                value={variable}
              >
                <div className={styles.colorSelectOption}>
                  <ColorPresenter
                    color={parsedValues[variable]}
                    borderless
                    style={{marginRight: 5}}
                  />
                  <span
                    className={
                      classNames(
                        styles.colorSelectOptionName,
                        'cp-ellipsis-text'
                      )
                    }
                  >
                    {VariableNames[variable] || variable}
                  </span>
                </div>
              </Select.Option>
            ))
          }
        </Select>
        {
          type !== types.color && this.renderFunction()
        }
        {
          initialValue !== modifiedValue && (
            <Button
              disabled={disabled}
              onClick={this.onRevert}
              className={classNames(styles.button, styles.small)}
            >
              <Icon type="rollback" />
            </Button>
          )
        }
        <Checkbox
          disabled={!extended || disabled}
          checked={!extended}
          className={styles.button}
          onChange={e => e.target.checked ? this.onClear() : undefined}
        >
          Inherited
        </Checkbox>
      </div>
    );
  }
}

ColorVariable.propTypes = {
  className: PropTypes.string,
  disabled: PropTypes.bool,
  variable: PropTypes.string,
  error: PropTypes.bool,
  value: PropTypes.string,
  modifiedValue: PropTypes.string,
  initialValue: PropTypes.string,
  extended: PropTypes.bool,
  parsedValues: PropTypes.object,
  parsedValue: PropTypes.string,
  onChange: PropTypes.func,
  variables: PropTypes.arrayOf(PropTypes.string)
};

export default ColorVariable;