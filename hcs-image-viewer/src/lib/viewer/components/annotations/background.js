/*
 * Copyright 2017-2023 EPAM Systems, Inc. (https://www.epam.com/)
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

import { CompositeLayer, COORDINATE_SYSTEM } from '@deck.gl/core';
import { PolygonLayer } from '@deck.gl/layers';
import getImageSize from '../../../state/utilities/get-image-size';

const AnnotationBackgroundLayer = class extends CompositeLayer {
  renderLayers() {
    const {
      loader,
      id,
    } = this.props;
    const { width = 0, height = 0 } = getImageSize(loader[0]) || {};
    const data = [{
      polygon: [
        [0, 0],
        [width, 0],
        [width, height],
        [0, height],
      ],
    }];
    const backgroundLayer = new PolygonLayer({
      id: `annotation-background-layer-${id}`,
      coordinateSystem: COORDINATE_SYSTEM.CARTESIAN,
      data,
      getLineWidth: () => 0,
      lineWidthUnits: 'pixels',
      getLineColor: () => ([0, 0, 0, 0]),
      getFillColor: () => ([0, 0, 0, 0]),
      filled: true,
      stroked: false,
      pickable: true,
    });
    return [backgroundLayer];
  }

  onClick() {
    const { onClick } = this.props;
    if (onClick) {
      onClick(undefined);
    }
    return true;
  }
};

AnnotationBackgroundLayer.layerName = 'AnnotationBackgroundLayer';
AnnotationBackgroundLayer.defaultProps = {
  loader: {
    type: 'object',
    value: {
      getRaster: async () => ({ data: [], height: 0, width: 0 }),
      getRasterSize: () => ({ height: 0, width: 0 }),
      dtype: '<u2',
    },
    compare: true,
  },
  id: { type: 'string', value: 'annotation-background-layer', compare: true },
  pickable: { type: 'boolean', value: true, compare: true },
  viewState: {
    type: 'object',
    value: { zoom: 0, target: [0, 0, 0] },
    compare: true,
  },
  onClick: { type: 'function', value: (() => {}), compare: true },
};

export default AnnotationBackgroundLayer;
