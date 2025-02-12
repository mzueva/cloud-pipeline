/*
 * Copyright 2017-2021 EPAM Systems, Inc. (https://www.epam.com/)
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
import {computed} from 'mobx';
import {observer} from 'mobx-react';
import {Modal} from 'antd';

import roleModel from '../../../utils/roleModel';
import SystemLogs from './system-logs';
import NATGateway from './nat-gateway-configuration/nat-gateway-configuration';
import SystemJobs from './system-jobs';
import SubSettings from '../sub-settings';
import DtsManagement from './dts';

@roleModel.authenticationInfo
@observer
export default class SystemManagement extends React.Component {
  state={
    modified: false,
    changesCanBeSkipped: false
  }

  componentDidMount () {
    const {route, router} = this.props;
    if (route && router) {
      router.setRouteLeaveHook(route, this.checkModifiedBeforeLeave);
    }
  }

  componentWillUnmount () {
    this.resetChangesStateTimeout && clearTimeout(this.resetChangesStateTimeout);
  }

  @computed
  get isAdmin () {
    const {authenticatedUserInfo} = this.props;
    if (authenticatedUserInfo &&
      authenticatedUserInfo.loaded) {
      return authenticatedUserInfo.value.admin;
    }
    return false;
  }

  @computed
  get dtsAllowed () {
    const {authenticatedUserInfo} = this.props;
    if (authenticatedUserInfo &&
      authenticatedUserInfo.loaded) {
      return authenticatedUserInfo.value.admin ||
        roleModel.isManager.dtsManager(this);
    }
    return false;
  }

  handleModified = (modified) => {
    if (this.state.modified !== modified) {
      this.setState({modified});
    }
  }

  confirmChangeURL = () => {
    return new Promise((resolve) => {
      if (this.state.modified) {
        Modal.confirm({
          title: 'You have unsaved changes. Continue?',
          style: {
            wordWrap: 'break-word'
          },
          onOk: () => {
            this.setState({modified: false});
            resolve(true);
          },
          onCancel () {
            resolve(false);
          },
          okText: 'Yes',
          cancelText: 'No'
        });
      } else {
        resolve(true);
      }
    });
  };

  checkModifiedBeforeLeave = (nextLocation) => {
    const {router} = this.props;
    const {changesCanBeSkipped, modified} = this.state;
    const resetChangesCanBeSkipped = () => {
      this.resetChangesStateTimeout = setTimeout(
        () => this.setState && this.setState({changesCanBeSkipped: false}),
        0
      );
    };
    const makeTransition = () => {
      this.setState({changesCanBeSkipped: true},
        () => {
          router.push(nextLocation);
          resetChangesCanBeSkipped();
        }
      );
    };
    if (modified && !changesCanBeSkipped) {
      this.confirmChangeURL()
        .then(confirmed => confirmed ? makeTransition() : undefined);
      return false;
    }
  };

  render () {
    return (
      <SubSettings
        sections={[
          ...(this.isAdmin ? [
            {
              key: 'logs',
              title: 'LOGS',
              default: true,
              render: () => (<SystemLogs />)
            },
            {
              key: 'nat',
              title: 'NAT GATEWAY',
              render: () => (<NATGateway handleModified={this.handleModified} />)
            },
            {
              key: 'jobs',
              title: 'SYSTEM JOBS',
              render: () => (<SystemJobs router={this.props.router} />)
            }
          ] : []),
          this.dtsAllowed ? (
            {
              key: 'dts',
              title: 'DTS',
              render: () => (<DtsManagement handleModified={this.handleModified} />)
            }
          ) : undefined
        ].filter(Boolean)}
        router={this.props.router}
        canNavigate={this.confirmChangeURL}
        root="system"
      />
    );
  }
}
