/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
import { PureComponent } from "react";
import { connect } from "react-redux";
import PropTypes from "prop-types";
import Immutable from "immutable";

import { SearchField } from "components/Fields";
import { loadSearchData } from "actions/search";
import { getSearchResult } from "selectors/resources";

import DatasetList from "./DatasetList";

const DELAY_SEARCH = 500;

// TODO combine with DatasetsSearch?

class SearchDatasets extends PureComponent {
  static propTypes = {
    searchData: PropTypes.instanceOf(Immutable.List).isRequired,
    loadSearchData: PropTypes.func.isRequired,
    changeSelectedNode: PropTypes.func.isRequired,
    dragType: PropTypes.string,
    isExpandable: PropTypes.bool,
    showAddIcon: PropTypes.bool,
    addFullPathToSqlEditor: PropTypes.func,
    style: PropTypes.object,
  };

  state = {
    filter: "",
  };

  UNSAFE_componentWillMount() {
    this.props.loadSearchData("");
  }

  handleFilter = (value) => {
    this.setState({
      filter: value,
    });

    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.props.loadSearchData(value);
    }, DELAY_SEARCH);
  };

  renderSearchField() {
    return (
      <SearchField
        value={this.state.filter}
        onChange={this.handleFilter}
        placeholder={laDeprecated("Search datasets…")}
        style={{ flexShrink: 0 }}
      />
    );
  }

  render() {
    const value = this.state.filter;
    const { dragType, searchData, changeSelectedNode, isExpandable } =
      this.props;
    return (
      <div
        className="resource-tree"
        style={{ ...styles.base, ...(this.props.style || {}) }}
      >
        {this.renderSearchField()}
        <DatasetList
          dragType={dragType}
          data={searchData}
          changeSelectedNode={changeSelectedNode}
          addFullPathToSqlEditor={this.props.addFullPathToSqlEditor}
          inputValue={value}
          style={styles.datasetList}
          isInProgress={false}
          showParents
          isExpandable={isExpandable}
          showAddIcon={this.props.showAddIcon}
          showSummaryOverlay={false}
        />
      </div>
    );
  }
}

const styles = {
  base: {
    display: "flex",
    flexDirection: "column",
    border: "1px solid var(--border--neutral)",
    overflowY: "auto", // this overflow is needed for FF. "flex: 1" doesn't correct work with overflow in FF
  },
  datasetList: {
    overflowY: "auto",
  },
  location: {
    margin: "7px 0 0",
  },
};

const mapStateToProps = (state) => ({
  searchData: getSearchResult(state) || Immutable.List(),
});

export default connect(mapStateToProps, { loadSearchData })(SearchDatasets);
