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
import {inject, observer} from 'mobx-react';
import {Link} from 'react-router';
import connect from '../../../utils/connect';
import {computed} from 'mobx';
import PropTypes from 'prop-types';
import dataStorageCache from '../../../models/dataStorage/DataStorageCache';
import MetadataUpdate from '../../../models/metadata/MetadataUpdate';
import MetadataDeleteKey from '../../../models/metadata/MetadataDeleteKey';
import MetadataDelete from '../../../models/metadata/MetadataDelete';
import DataStorageTagsUpdate from '../../../models/dataStorage/tags/DataStorageTagsUpdate';
import DataStorageTagsDelete from '../../../models/dataStorage/tags/DataStorageTagsDelete';
import LoadingView from '../../special/LoadingView';
import {Alert, Button, Col, Icon, Input, message, Modal, Row, Tooltip} from 'antd';
import styles from './Metadata.css';
import {SplitPanel} from '../splitPanel/SplitPanel';
import localization from '../../../utils/localization';
import UserName from '../../special/UserName';

const AutoGeneratedTags = {
  owner: 'CP_OWNER',
  source: 'CP_SOURCE',
  runId: 'CP_RUN_ID',
  job: {
    id: 'CP_JOB_ID',
    name: 'CP_JOB_NAME',
    version: 'CP_JOB_VERSION',
    configuration: 'CP_JOB_CONFIGURATION'
  },
  dockerImage: 'CP_DOCKER_IMAGE',
  calculationConfig: 'CP_CALC_CONFIG'
};

const metadataLoad = (params, metadataCache, dataStorageCache) => {
  if (params.entityClass === 'DATA_STORAGE_ITEM') {
    return dataStorageCache.getTags(params.entityParentId, params.entityId, params.entityVersion);
  } else {
    return metadataCache.getMetadata(params.entityId, params.entityClass);
  }
};

const previewLoad = (params, dataStorageCache) => {
  if (params.entityClass === 'DATA_STORAGE_ITEM' && !params.fileIsEmpty) {
    return dataStorageCache.getContent(
      params.entityParentId,
      params.entityId,
      params.entityVersion
    );
  } else {
    return null;
  }
};

const downloadUrlLoad = (params, dataStorageCache) => {
  if (params.entityClass === 'DATA_STORAGE_ITEM' && !params.fileIsEmpty) {
    return dataStorageCache.getDownloadUrl(
      params.entityParentId,
      params.entityId,
      params.entityVersion
    );
  } else {
    return null;
  }
};

const MetadataDisplayOptions = {
  preview: {
    maxLength: 100,
    display: function (value) {
      if (value && value.length > this.maxLength) {
        return (
          <Tooltip
            overlayClassName="metadata-entry-tooltip-container"
            mouseEnterDelay="1"
            title={value}
            placement="left">
            <span>{value.substring(0, this.maxLength)}...</span>
          </Tooltip>
        );
      }
      return value;
    }
  },
  edit: {
    autosize: {
      maxRows: 6,
      minRows: undefined
    }
  }
};

@connect({
  dataStorageCache
})
@localization.localizedComponent
@inject('metadataCache', 'pipelines', 'dockerRegistries')
@inject(({metadataCache, dataStorageCache, pipelines, dockerRegistries}, params) => ({
  pipelines,
  dockerRegistries,
  metadataCache,
  dataStorageCache,
  metadata: metadataLoad(params, metadataCache, dataStorageCache),
  dataStorageTags: params.entityClass === 'DATA_STORAGE_ITEM',
  preview: previewLoad(params, dataStorageCache),
  downloadUrl: downloadUrlLoad(params, dataStorageCache)
}))
@observer
export default class Metadata extends localization.LocalizedReactComponent {

  static propTypes = {
    readOnly: PropTypes.bool,
    hideMetadataTags: PropTypes.bool,
    entityName: PropTypes.string,
    entityClass: PropTypes.string,
    entityId: PropTypes.oneOfType([
      PropTypes.string,
      PropTypes.number
    ]),
    entityParentId: PropTypes.oneOfType([
      PropTypes.string,
      PropTypes.number
    ]),
    entityVersion: PropTypes.string,
    canNavigateBack: PropTypes.bool,
    onNavigateBack: PropTypes.func,
    fileIsEmpty: PropTypes.bool
  };

  state = {
    addKey: null
  };

  confirmDeleteMetadata = () => {
    Modal.confirm({
      title: 'Do you want to delete all metadata?',
      style: {
        wordWrap: 'break-word'
      },
      content: null,
      okText: 'OK',
      cancelText: 'Cancel',
      onOk: async () => {
        let request;
        if (this.props.dataStorageTags) {
          request = new DataStorageTagsDelete(this.props.entityParentId, this.props.entityId, this.props.entityVersion);
          await request.send(this.metadata.map(m => m.key));
        } else {
          request = new MetadataDelete();
          await request.send({
            entityId: this.props.entityId,
            entityClass: this.props.entityClass
          });
        }
        if (request.error) {
          message.error(request.error, 5);
        } else {
          this.props.metadata.fetch();
        }
      }
    });
  };

  confirmDeleteKey = (item) => {
    Modal.confirm({
      title: `Do you want to delete key "${item.key}"?`,
      content: null,
      style: {
        wordWrap: 'break-word'
      },
      okText: 'OK',
      cancelText: 'Cancel',
      onOk: async () => {
        let request;
        if (this.props.dataStorageTags) {
          request = new DataStorageTagsDelete(this.props.entityParentId, this.props.entityId, this.props.entityVersion);
          await request.send([item.key]);
        } else {
          request = new MetadataDeleteKey(item.key);
          await request.send({
            entityId: this.props.entityId,
            entityClass: this.props.entityClass
          });
        }
        if (request.error) {
          message.error(request.error, 5);
        } else {
          this.props.metadata.fetch();
        }
      }
    });
  };

  autoFocusInputRef = (input) => {
    if (input && input.refs && input.refs.input && input.refs.input.focus) {
      input.refs.input.focus();
    }
  };

  saveMetadata = (opts) => async () => {
    const {index, field} = opts;
    const metadata = this.metadata;
    const [currentMetadataItem] = metadata.filter(m => m.index === index);
    if (currentMetadataItem) {
      const value = this.state.editableText;
      if ((field === 'key' && currentMetadataItem.key === value) ||
        (field === 'value' && currentMetadataItem.value === value)) {
        this.setState({editableKeyIndex: null, editableValueIndex: null, editableText: null});
        return;
      }
      if (field === 'key') {
        if (!value || !value.length || !value.trim().length) {
          message.error('Key should not be empty', 5);
          return;
        }
        const [existedMetadataItem] = metadata.filter(m => m.index !== index && m.key === value);
        if (existedMetadataItem) {
          message.error(`Key '${value}' already exists.`, 5);
          return;
        }
        currentMetadataItem.key = value.trim();
      } else if (field === 'value') {
        currentMetadataItem.value = value;
      }
      let request;
      if (this.props.dataStorageTags) {
        const payload = {};
        for (let i = 0; i < metadata.length; i++) {
          payload[metadata[i].key] = metadata[i].value;
        }
        request = new DataStorageTagsUpdate(this.props.entityParentId, this.props.entityId, this.props.entityVersion);
        await request.send(payload);
      } else {
        const payload = {};
        for (let i = 0; i < metadata.length; i++) {
          payload[metadata[i].key] = {
            value: metadata[i].value,
            type: metadata[i].type
          };
        }
        request = new MetadataUpdate();
        await request.send({
          entity: {
            entityId: this.props.entityId,
            entityClass: this.props.entityClass,
          },
          data: payload
        });
      }
      if (request.error) {
        message.error(request.error, 5);
      } else {
        this.props.metadata.fetch();
      }
    } else if (this.state.addKey) {
      if (!this.state.addKey.key || !this.state.addKey.key.length || !this.state.addKey.key.trim().length) {
        message.error('Enter key', 5);
        return;
      }
      const [existedMetadataItem] = metadata.filter(m => m.key === this.state.addKey.key);
      if (existedMetadataItem) {
        message.error(`Key '${this.state.addKey.key}' already exists.`, 5);
        return;
      }
      if (!this.state.addKey.value || !this.state.addKey.value.length) {
        message.error('Enter value', 5);
        return;
      }
      let request;
      if (this.props.dataStorageTags) {
        const payload = {
          [this.state.addKey.key.trim()]: this.state.addKey.value
        };
        for (let i = 0; i < metadata.length; i++) {
          payload[metadata[i].key] = metadata[i].value;
        }
        request = new DataStorageTagsUpdate(this.props.entityParentId, this.props.entityId, this.props.entityVersion);
        await request.send(payload);
      } else {
        const payload = {
          [this.state.addKey.key]: {
            value: this.state.addKey.value,
            type: 'string'
          }
        };
        for (let i = 0; i < metadata.length; i++) {
          payload[metadata[i].key] = {
            value: metadata[i].value,
            type: metadata[i].type
          };
        }
        request = new MetadataUpdate();
        await request.send({
          entity: {
            entityId: this.props.entityId,
            entityClass: this.props.entityClass,
          },
          data: payload
        });
      }
      if (request.error) {
        message.error(request.error, 5);
      } else {
        this.props.metadata.fetch();
      }
    }
    this.setState({
      addKey: null,
      editableKeyIndex: null,
      editableValueIndex: null,
      editableText: null
    });
  };

  onMetadataEditStarted = (field, index, value) => () => {
    if (this.props.readOnly) {
      return;
    }
    if (field === 'key') {
      this.setState({addKey: null, editableKeyIndex: index, editableValueIndex: null, editableText: value});
    } else if (field === 'value') {
      this.setState({addKey: null, editableKeyIndex: null, editableValueIndex: index, editableText: value});
    }
  };

  onMetadataChange = (e) => {
    this.setState({editableText: e.target.value});
  };

  discardChanges = () => {
    this.setState({
      addKey: null,
      editableKeyIndex: null,
      editableValueIndex: null,
      editableText: null
    });
  };

  isAutoGeneratedItem = (key) => {
    return [AutoGeneratedTags.calculationConfig,
      AutoGeneratedTags.dockerImage,
      AutoGeneratedTags.job.configuration,
      AutoGeneratedTags.job.id,
      AutoGeneratedTags.job.name,
      AutoGeneratedTags.job.version,
      AutoGeneratedTags.owner,
      AutoGeneratedTags.runId,
      AutoGeneratedTags.source
    ].indexOf(key) >= 0;
  };

  renderAutogeneratedTag = (key, title, icon, link, value) => {
    return [
      <tr key={`${key}_key`} className={styles.readOnlyKeyRow}>
        <td
          id={`key-column-${key}`}
          colSpan={6}
          className={styles.key}
          style={{textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap'}}>
          <Icon type={icon} /> {title}
        </td>
      </tr>,
      <tr key={`${key}_value`} className={styles.readOnlyValueRow}>
        <td
          id={`value-column-${key}`}
          colSpan={6}>
          {
            link
              ? <Link to={link}>{value}</Link>
              : value
          }
        </td>
      </tr>
    ];
  };

  renderOwnerTag = (metadata) => {
    const [ownerTag] = metadata.filter(item => item.key === AutoGeneratedTags.owner);
    if (ownerTag) {
      return this.renderAutogeneratedTag(ownerTag.key, 'Owner', 'user', false, <UserName userName={ownerTag.value} />);
    }
    return [];
  };

  renderSourceTag = (metadata) => {
    const [sourceTag] = metadata.filter(item => item.key === AutoGeneratedTags.source);
    if (sourceTag) {
      return this.renderAutogeneratedTag(sourceTag.key, 'Source', 'download', false, sourceTag.value);
    }
    return [];
  };

  renderRunTag = (metadata) => {
    const [runIdTag] = metadata.filter(item => item.key === AutoGeneratedTags.runId);
    if (runIdTag) {
      return this.renderAutogeneratedTag(
        runIdTag.key,
        'RunID',
        'play-circle-o',
        `/run/${runIdTag.value}`,
        runIdTag.value
      );
    }
    return [];
  };

  renderJobTag = (metadata) => {
    const [pipelineIdTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.id);
    const [pipelineNameTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.name);
    let pipelineName = 'Unknown';
    if (!pipelineNameTag && pipelineIdTag) {
      const pipelines = this.props.pipelines.loaded ? (this.props.pipelines.value || []).map(p => p) : [];
      const [pipeline] = pipelines.filter(p => `${p.id}` === `${pipelineIdTag.value}`);
      if (pipeline) {
        pipelineName = pipeline.name;
      }
    } else if (pipelineNameTag) {
      pipelineName = pipelineNameTag.value;
    }
    if (pipelineIdTag) {
      return this.renderAutogeneratedTag(
        pipelineIdTag.key,
        this.localizedString('Pipeline'),
        'fork',
        `/${pipelineIdTag.value}`,
        pipelineName
      );
    }
    return [];
  };

  renderJobVersionTag = (metadata) => {
    const [pipelineVersionTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.version);
    const [pipelineIdTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.id);
    if (pipelineIdTag && pipelineVersionTag) {
      return this.renderAutogeneratedTag(
        pipelineVersionTag.key,
        `${this.localizedString('Pipeline')} version`,
        'tag-o',
        `/${pipelineIdTag.value}/${pipelineVersionTag.value}`,
        pipelineVersionTag.value
      );
    }
    return [];
  };

  renderJobConfigurationTag = (metadata) => {
    const [pipelineIdTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.id);
    const [pipelineVersionTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.version);
    const [pipelineConfigurationTag] = metadata.filter(item => item.key === AutoGeneratedTags.job.configuration);
    if (pipelineIdTag && pipelineVersionTag && pipelineConfigurationTag) {
      return this.renderAutogeneratedTag(
        pipelineConfigurationTag.key,
        `${this.localizedString('Pipeline')} configuration`,
        'setting',
        `/${pipelineIdTag.value}/${pipelineVersionTag.value}/configuration/${pipelineConfigurationTag.value}`,
        pipelineConfigurationTag.value
      );
    }
    return [];
  };

  renderDockerImageTag = (metadata) => {
    const [dockerImageTag] = metadata.filter(item => item.key === AutoGeneratedTags.dockerImage);
    let dockerImageId = null;
    if (dockerImageTag && this.props.dockerRegistries.loaded) {
      const [registryPath, groupName, toolWithVersion] = dockerImageTag.value.split('/');
      const [toolName] = toolWithVersion.split(':');
      const [registry] = (this.props.dockerRegistries.value.registries || [])
        .filter(r => r.path.toLowerCase() === (registryPath || '').toLowerCase());
      if (registry) {
        const [group] = (registry.groups || []).filter(g => g.name.toLowerCase() === (groupName || '').toLowerCase());
        if (group) {
          const [tool] = (group.tools || [])
            .filter(t => t.image.toLowerCase() === `${(groupName || '')}/${toolName}`.toLowerCase());
          if (tool) {
            dockerImageId = tool.id;
          }
        }
      }
    }
    if (dockerImageTag) {
      return this.renderAutogeneratedTag(
        dockerImageTag.key,
        'Docker image',
        'tool',
        dockerImageId ? `/tool/${dockerImageId}` : null,
        dockerImageTag.value
      );
    }
    return [];
  };

  renderCalculationConfigTag = (metadata) => {
    const [calcConfigTag] = metadata.filter(item => item.key === AutoGeneratedTags.calculationConfig);
    if (calcConfigTag) {
      let [instanceType, workersCount, totalCPU] = calcConfigTag.value.split(':');
      instanceType = (instanceType || '').toLowerCase() === 'none' ? null : instanceType;
      workersCount = (workersCount || '').toLowerCase() === 'none' ? null : workersCount;
      totalCPU = (totalCPU || '').toLowerCase() === 'none' ? null : totalCPU;
      const value = (
        <ul style={{listStyle: 'disc inside'}}>
          {
            instanceType &&
            <li>Instance type: {instanceType}</li>
          }
          {
            workersCount &&
            <li>Workers count: {workersCount}</li>
          }
          {
            totalCPU &&
            <li>Total CPU: {totalCPU}</li>
          }
        </ul>
      );
      return this.renderAutogeneratedTag(
        calcConfigTag.key,
        'Compute',
        'calculator',
        null,
        value
      );
    }
    return [];
  };

  renderAutogeneratedMetadata = (metadata) => {
    if (!this.props.dataStorageTags) {
      return null;
    }
    return [
      ...this.renderOwnerTag(metadata),
      ...this.renderSourceTag(metadata),
      ...this.renderRunTag(metadata),
      ...this.renderJobTag(metadata),
      ...this.renderJobVersionTag(metadata),
      ...this.renderJobConfigurationTag(metadata),
      ...this.renderDockerImageTag(metadata),
      ...this.renderCalculationConfigTag(metadata)
    ];
  };

  renderMetadataItem = (metadataItem) => {
    if (this.props.dataStorageTags && this.isAutoGeneratedItem(metadataItem.key)) {
      return null;
    }
    let keyElement;
    let valueElement;
    const inputOptions = (field) => {
      return {
        id: `${field}-input-${metadataItem.key}`,
        ref: this.autoFocusInputRef,
        onBlur: this.saveMetadata({index: metadataItem.index, field}),
        onPressEnter: this.saveMetadata({index: metadataItem.index, field}),
        size: 'small',
        value: this.state.editableText,
        onChange: this.onMetadataChange,
        onKeyDown: (e) => {
          if (e.key && e.key === 'Escape') {
            this.discardChanges();
          }
        }
      };
    };
    if (this.state.editableKeyIndex === metadataItem.index) {
      keyElement = (
        <tr key={`${metadataItem.key}_key`} className={styles.keyRowEdit}>
          <td colSpan={6}>
            <Input {...inputOptions('key')} />
          </td>
        </tr>
      );
    } else {
      keyElement = (
        <tr key={`${metadataItem.key}_key`} className={this.props.readOnly ? styles.readOnlyKeyRow : styles.keyRow}>
          <td
            id={`key-column-${metadataItem.key}`}
            colSpan={this.props.readOnly ? 6 : 5}
            className={styles.key}
            style={{textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap'}}
            onClick={this.onMetadataEditStarted('key', metadataItem.index, metadataItem.key)}>
            {metadataItem.key}
          </td>
          {
            this.props.readOnly ? undefined :
              (
                <td style={{minWidth: 30, textAlign: 'right'}}>
                  <Button
                    id={`delete-metadata-key-${metadataItem.key}-button`}
                    type="danger"
                    size="small"
                    onClick={() => this.confirmDeleteKey(metadataItem)}>
                    <Icon type="delete" />
                  </Button>
                </td>
              )
          }
        </tr>
      );
    }
    if (this.state.editableValueIndex === metadataItem.index) {
      valueElement = (
        <tr key={`${metadataItem.key}_value`} className={styles.valueRowEdit}>
          <td colSpan={6}>
            <Input
              {...inputOptions('value')}
              type="textarea"
              autosize={MetadataDisplayOptions.edit.autosize} />
          </td>
        </tr>
      );
    } else {
      valueElement = (
        <tr key={`${metadataItem.key}_value`} className={this.props.readOnly ? styles.readOnlyValueRow : styles.valueRow}>
          <td
            id={`value-column-${metadataItem.key}`}
            colSpan={6} onClick={this.onMetadataEditStarted('value', metadataItem.index, metadataItem.value)}>
            {MetadataDisplayOptions.preview.display(metadataItem.value)}
          </td>
        </tr>
      );
    }
    return [this.getDivider(`${metadataItem.key}_divider`, 6), keyElement, valueElement];
  };

  getDivider = (key, span) => {
    return (
      <tr key={key} className={styles.divider}>
        <td colSpan={span || 3}><div /></td>
      </tr>
    );
  };

  @computed
  get metadata () {
    if (this.props.metadata.error || this.props.metadata.pending) {
      return [];
    }
    const value = [];
    if (this.props.dataStorageTags) {
      const data = this.props.metadata.value;
      for (let property in data) {
        if (data.hasOwnProperty(property)) {
          value.push({
            key: property,
            value: data[property]
          });
        }
      }
    } else {
      const [data] = (this.props.metadata.value || []).filter(key => key && key.data).map(key => key.data);
      if (data) {
        for (let property in data) {
          if (data.hasOwnProperty(property)) {
            value.push({
              key: property,
              value: data[property].value,
              type: data[property].type
            });
          }
        }
      }
    }
    value.sort((a, b) => {
      if (a.key < b.key) {
        return -1;
      } else if (a.key > b.key) {
        return 1;
      }
      return 0;
    });
    return value.map((value, index) => { return {...value, index}; });
  }

  @computed
  get filePreview () {
    if (this.props.preview) {
      if (this.props.preview.pending) {
        return null;
      }
      const preview = this.props.preview.value.content
        ? atob(this.props.preview.value.content)
        : '';
      const truncated = this.props.preview.value.truncated;
      const noContent = !preview;
      const mayBeBinary = this.props.preview.value.mayBeBinary;
      const error = this.props.preview.error;

      return {
        preview,
        truncated,
        noContent,
        error,
        mayBeBinary
      };
    } else if (this.props.fileIsEmpty) {
      return {
        preview: '',
        mayBeBinary: false,
        truncated: false,
        noContent: true,
        error: null
      };
    }

    return null;
  }

  @computed
  get downloadUrl () {
    if (this.props.downloadUrl) {
      if (this.props.downloadUrl.error || this.props.downloadUrl.pending) {
        return null;
      }
      return this.props.downloadUrl.value.url;
    }

    return null;
  }

  renderAddKeyRow = () => {
    if (this.state.addKey) {
      const addKeyCancelClicked = () => {
        this.setState({editableKeyIndex: null, editableValueIndex: null, editableText: null, addKey: null});
      };

      const refKeyInput = (input) => {
        if (this.state.addKey &&
          !this.state.addKey.autofocused &&
          input && input.refs && input.refs.input && input.refs.input.focus) {
          input.refs.input.focus();
          const addKey = this.state.addKey;
          addKey.autofocused = true;
          this.setState({addKey});
        }
      };

      const onChange = (field) => (e) => {
        const addKey = this.state.addKey;
        addKey[field] = e.target.value;
        this.setState({addKey});
      };

      const onEnter = (e) => {
        e.stopPropagation();
        this.saveMetadata({})();
        return false;
      };

      return [
        this.getDivider('new key'),
        <tr className={styles.newKeyRow} key="new key row">
          <td style={{textAlign: 'right', width: 80}}>
            Key:
          </td>
          <td colSpan={2}>
            <Input
              ref={refKeyInput}
              onKeyDown={(e) => {
                if (e.key && e.key === 'Escape') {
                  this.discardChanges();
                }
              }}
              value={this.state.addKey.key}
              onChange={onChange('key')}
              size="small" />
          </td>
        </tr>,
        <tr className={styles.newKeyRow} key="new value row">
          <td style={{textAlign: 'right', width: 80}}>
            Value:
          </td>
          <td colSpan={2}>
            <Input
              onPressEnter={onEnter}
              onKeyDown={(e) => {
                if (e.key && e.key === 'Escape') {
                  this.discardChanges();
                }
              }}
              value={this.state.addKey.value}
              onChange={onChange('value')}
              size="small"
              type="textarea"
              autosize={true} />
          </td>
        </tr>,
        <tr className={styles.newKeyRow} key="new key title row">
          <td colSpan={3} style={{textAlign: 'right'}}>
            <Button
              id="add-metadata-item-button"
              size="small"
              type="primary"
              onClick={this.saveMetadata({})}>
              <Icon type="check" /> Add
            </Button>
            <Button
              id="cancel-add-metadata-item-button"
              size="small"
              onClick={addKeyCancelClicked}>
              <Icon type="close" /> Cancel
            </Button>
          </td>
        </tr>
      ];
    } else {
      return undefined;
    }
  };

  renderEmptyPlaceholder = () => {
    return (
      <tr style={{height: 40, color: '#777'}}>
        <td colSpan={3} style={{textAlign: 'center'}}>
          No attributes set
        </td>
      </tr>
    );
  };

  renderNoPreviewAvailable = () => {
    return (
      <Row type="flex" key="preview body" style={{height: 40, color: '#777', margin: '0 auto'}}>
        No preview available
      </Row>
    );
  };

  renderFilePreview = () => {
    if (this.props.preview && this.props.preview.pending) {
      return <LoadingView key="loading_view" />;
    }
    const previewRes = [];

    if (!this.filePreview) {
      previewRes.push(this.renderNoPreviewAvailable());
      return previewRes;
    }

    const {preview, truncated, noContent, error, mayBeBinary} = this.filePreview;
    if (error) {
      previewRes.push(
        <div key="body" style={{width: '100%', flex: 1, overflowY: 'auto', paddingTop: 10}}>
          <Alert type="error" message={error} />
        </div>
      );
      return previewRes;
    }
    if (!mayBeBinary) {
      previewRes.push(
        <Row
          type="flex"
          justify="space-between"
          key="preview heading"
          style={{color: '#777', marginTop: 5, marginBottom: 5}}
        >
          <Col colSpan={2}>
            File preview
          </Col>
          <Col style={{textAlign: 'right'}}>
            <Button
              onClick={this.props.openEditFileForm}
              size="small" style={{border: 'none'}}>
              <Icon type="arrows-alt" />
            </Button>
          </Col>
        </Row>
      );
    }
    if (noContent && !mayBeBinary) {
      previewRes.push(
        <Row type="flex" key="preview body" style={{height: 40, color: '#777', margin: '0 auto'}}>
          No content
        </Row>
      );
      return previewRes;
    }
    if (!mayBeBinary) {
      previewRes.push(
        <Row id="file-preview-container" type="flex" key="preview body" style={{color: '#777', flex: 1}}>
          <Input
            spellCheck="false"
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            type="textarea"
            className={styles.disabledTextarea}
            value={preview}
            readOnly
          />
        </Row>
      );
    }
    if (mayBeBinary) {
      const downloadUrl = this.downloadUrl;
      if (downloadUrl) {
        previewRes.push(
          <Row type="flex" key="preview footer" style={{color: '#777', marginTop: 5, marginBottom: 5}}>
            File preview is not available. <a style={{marginLeft: 5, marginRight: 5}} href={downloadUrl} download={this.props.entityId}>Download file</a> to view full contents
          </Row>
        );
      }
    } else if (truncated) {
      const downloadUrl = this.downloadUrl;
      if (downloadUrl) {
        previewRes.push(
          <Row type="flex" key="preview footer" style={{color: '#777', marginTop: 5, marginBottom: 5}}>
            File is too large to be shown. <a style={{marginLeft: 5, marginRight: 5}} href={downloadUrl} download={this.props.entityId}>Download file</a> to view full contents
          </Row>
        );
      }
    }
    return previewRes;
  };

  renderTableHeader = (editable) => {
    const addKeyClicked = () => {
      this.setState({
        editableKeyIndex: null,
        editableValueIndex: null,
        editableText: null,
        addKey: {
          key: '',
          value: ''
        }
      });
    };
    const renderTitle = () => {
      if (this.props.entityName && this.props.onNavigateBack && this.props.canNavigateBack) {
        const titleParts = [];
        titleParts.push(
          <Button
            id="back-button"
            key="back-button"
            style={{marginRight: 5}}
            size="small"
            onClick={this.props.onNavigateBack}>
            <Icon type="left" />
          </Button>
        );
        titleParts.push(<b key="entity name">{this.props.entityName}</b>);
        return titleParts;
      }
      return undefined;
    };
    const renderActions = () => {
      const actions = [];
      if (editable && !this.state.addKey) {
        actions.push(
          <Button
            id="add-key-button"
            key="add button"
            size="small"
            onClick={addKeyClicked}>
            <Icon type="plus" /> Add
          </Button>
        );
      }
      if (editable && this.metadata.length > 0) {
        actions.push(
          <Button
            id="remove-all-keys-button"
            key="remove all keys button"
            size="small"
            type="danger"
            onClick={this.confirmDeleteMetadata}>
            <Icon type="delete" /> Remove all
          </Button>
        );
      }
      return actions;
    };
    return (
      <thead className={styles.metadataHeader}>
        <tr style={{}}>
          <td colSpan={3} style={{padding: 5}}>
            <Row type="flex" justify="space-between" align="middle">
              <div>
                {renderTitle()}
              </div>
              <div>
                {renderActions()}
              </div>
            </Row>
          </td>
        </tr>
      </thead>
    );
  };

  renderMetadataTable = () => {
    const header = (
      <table key="header" style={{width: '100%'}}>
        {this.renderTableHeader(
          !this.props.readOnly && (!this.props.hideMetadataTags || !this.props.dataStorageTags)
        )}
        <tbody>
          {this.renderAddKeyRow()}
        </tbody>
      </table>
    );
    let metadata;
    if (this.props.metadata.error) {
      metadata = (
        <div key="body" style={{width: '100%', flex: 1, overflowY: 'auto', paddingTop: 10}}>
          <Alert type="error" message={this.props.metadata.error} />
        </div>
      );
    } else if (!this.props.hideMetadataTags || !this.props.dataStorageTags) {
      metadata = (
        <div key="body" style={{width: '100%', flex: 1, overflowY: 'auto'}}>
          <table key="body" style={{width: '100%', tableLayout: 'fixed'}}>
            <tbody>
            {
              this.renderAutogeneratedMetadata(this.metadata)
            }
            {
              this.metadata.map(this.renderMetadataItem).reduce((arr, val) => {
                if (!arr) {
                  arr = [];
                }
                arr.push(val);
                return arr;
              }, [])
            }
            {
              this.metadata.length === 0 ? this.renderEmptyPlaceholder() : undefined
            }
            </tbody>
          </table>
        </div>
      );
    }
    const result = [header];
    if (this.props.dataStorageTags) {
      result.push(
        <SplitPanel
          key="split"
          style={{flex: 1}}
          orientation="vertical">
          <div key="file preview" style={{
            display: 'flex',
            flexDirection: 'column',
            height: '100%'
          }}>
            {this.renderFilePreview()}
          </div>
          {this.props.hideMetadataTags ? null : metadata}
        </SplitPanel>
      );
    } else {
      result.push(metadata);
    }
    return result;
  };

  render () {
    if (this.props.metadata.pending) {
      return <LoadingView />;
    }
    return (
      <Row type="flex" style={{flex: 1, display: 'flex', flexDirection: 'column'}}>
        {this.renderMetadataTable()}
      </Row>
    );
  }

  refresh = () => {
    this.props.metadata.fetch();
    if (this.props.dataStorageTags) {
      this.props.dataStorageTags.fetch();
    }
    if (this.props.preview) {
      this.props.preview.fetch();
    }
    if (this.props.downloadUrl) {
      this.props.downloadUrl.fetch();
    }
  };

  componentWillReceiveProps (nextProps) {
    if (this.props.entityClass !== nextProps.entityClass ||
      this.props.entityId !== nextProps.entityId ||
      this.props.entityParentId !== nextProps.entityParentId) {
      if (nextProps.entityClass === 'DATA_STORAGE_ITEM') {
        this.props.dataStorageCache.invalidateTags(
          nextProps.entityParentId,
          nextProps.entityId,
          nextProps.entityVersion
        );
        this.props.dataStorageCache.invalidateContent(
          nextProps.entityParentId,
          nextProps.entityId,
          nextProps.entityVersion
        );
        this.props.dataStorageCache.invalidateDownloadUrl(
          nextProps.entityParentId,
          nextProps.entityId,
          nextProps.entityVersion
        );
      } else {
        this.props.metadataCache.invalidateMetadata(nextProps.entityId, nextProps.entityClass);
      }
    }
  }
}
