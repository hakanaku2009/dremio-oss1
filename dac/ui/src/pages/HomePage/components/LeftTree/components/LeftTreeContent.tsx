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
import { useDispatch } from "react-redux";
import { toggleDatasetsExpanded } from "../../../../../actions/ui/ui";
import { useLeftTreeExpandedStates } from "../LeftTreeUtils";
import { SourcesSection } from "./SourceSections/SourcesSection";
import { HomeSection } from "./HomeSection/HomeSection";
import SpacesSection from "../../../../../pages/HomePage/components/SpacesSection";

export const LeftTreeContent = ({
  sources,
  sourcesViewState,
}: {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<string, any>;
  homeSourceUrl: string | null;
}) => {
  const dispatch = useDispatch();
  const { datasetsExpanded } = useLeftTreeExpandedStates();
  return (
    <>
      <HomeSection />
      <SpacesSection
        isCollapsed={datasetsExpanded}
        isCollapsible
        onToggle={() => dispatch(toggleDatasetsExpanded())}
      />
      <SourcesSection sources={sources} sourcesViewState={sourcesViewState} />
    </>
  );
};
