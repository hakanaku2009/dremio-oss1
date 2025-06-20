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

import Immutable from "immutable";
import { FC, useContext, useDeferredValue, useMemo } from "react";
import {
  renderExpandedIcon,
  TreeItemLevelContextProvider,
  useTreeItem,
} from "../../Tree";
import type {
  FolderCatalogObject,
  FolderCatalogReference,
} from "@dremio/dremio-js/oss";
import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import {
  catalogByPath,
  catalogReferenceChildren,
} from "@inject/queries/catalog";
import { getIdListString } from "#oss/exports/utilities/getIdListString";
import { CatalogTreeChildren } from "../CatalogTreeChildren";
import { AddToQueryButton } from "./components/AddToQueryButton";
import { OpenDetailsButton } from "./components/OpenDetailsButton";
import { StarredButton } from "@inject/exports/components/CatalogTree/CatalogTreeItems/components/StarredButton";
import EntitySummaryOverlay from "#oss/components/EntitySummaryOverlay/EntitySummaryOverlay";
import { TreeConfigContext } from "#oss/components/Tree/treeConfigContext";
import { getFullPathFromCatalogReference } from "#oss/components/Tree/resourceTreeUtils";
import DragSource from "#oss/components/DragComponents/DragSource";
import { CatalogObjectDisplay } from "../CatalogObjectDisplay";
import { PathPrefixContext } from "../prefixContext";

export const FolderCatalogTreeItem: FC<{
  catalogReference: FolderCatalogReference;
}> = (props) => {
  const catalogObject = useSuspenseQuery(
    catalogByPath(getSonarContext().getSelectedProjectId?.())(
      props.catalogReference.path,
    ),
  ).data.unwrap() as FolderCatalogObject;

  const pathPrefix = useContext(PathPrefixContext);

  const treeItemProps = useTreeItem(
    (pathPrefix ? `${pathPrefix}-` : "") + props.catalogReference.id,
  );

  const childrenQuery = useQuery({
    ...catalogReferenceChildren(props.catalogReference),
    enabled: treeItemProps.isExpanded,
  });

  // pathPrefix always exists for folders unless they are at the root level in the Starred tab
  const newPrefix = pathPrefix
    ? `${pathPrefix}-${props.catalogReference.name.replace(/\s+/g, "")}`
    : props.catalogReference.name.replace(/\s+/g, "");

  const childrenIds = useMemo(() => {
    if (!childrenQuery.data) {
      return undefined;
    }

    return getIdListString(childrenQuery.data, newPrefix);
  }, [childrenQuery.data, newPrefix]);

  const expandedDeferred = useDeferredValue(treeItemProps.isExpanded);

  const { handleDatasetDetails } = useContext(TreeConfigContext);

  return (
    <>
      <div
        aria-owns={childrenIds}
        aria-label={props.catalogReference.name}
        {...treeItemProps}
      >
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          {renderExpandedIcon(
            treeItemProps.isExpanded,
            childrenQuery.isFetching && treeItemProps.isExpanded,
          )}
          <DragSource
            dragType="explorePage"
            id={Immutable.fromJS(props.catalogReference.path)}
            className="overflow-hidden"
          >
            <CatalogObjectDisplay
              catalogObject={catalogObject}
              summaryOverlay={
                <EntitySummaryOverlay
                  name={props.catalogReference.name}
                  type={props.catalogReference.type}
                  fullPath={Immutable.fromJS(props.catalogReference.path)}
                  openDetailsPanel={handleDatasetDetails}
                  entityUrl={getFullPathFromCatalogReference(
                    props.catalogReference,
                  )}
                />
              }
            />
          </DragSource>
        </div>
        <div className="ml-auto catalog-treeitem__actions">
          <OpenDetailsButton catalogReference={props.catalogReference} />
          <AddToQueryButton catalogReference={props.catalogReference} />
          <StarredButton catalogReference={props.catalogReference} />
        </div>
      </div>
      {expandedDeferred && childrenQuery.data ? (
        <PathPrefixContext.Provider value={newPrefix}>
          <TreeItemLevelContextProvider>
            <CatalogTreeChildren catalogReferences={childrenQuery.data} />
          </TreeItemLevelContextProvider>
        </PathPrefixContext.Provider>
      ) : null}
    </>
  );
};
