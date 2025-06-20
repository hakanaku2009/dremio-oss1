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

import { SmartResource } from "smart-resource1";
import { listReflectionJobs } from "../endpoints/JobsListing/listReflectionJobs";
import { formatJobsBackendQuery } from "#oss/pages/JobsPage/jobs-page-utils";
import { JobsQueryParams } from "dremio-ui-common/types/Jobs.types";
import moize from "moize";
import { PollingResource } from "../utilities/PollingResource";

export const jobsCache = moize.promise(listReflectionJobs, {
  maxAge: 30000,
  maxSize: 100,
  isDeepEqual: true,
});

const paginatedReflectionJobsFetcher = async (
  reflectionId: string,
  pageCount: number,
  query: JobsQueryParams,
) => {
  let result;
  let next = null;
  for (let pageNum = 0; pageNum < pageCount; pageNum++) {
    if (pageNum > 0 && !next) {
      break;
    }

    const val: any = await jobsCache({
      reflectionId,
      // Spread on top-level so the deep-equal comparison checks children correctly
      ...formatJobsBackendQuery({
        sort: query.sort,
        order: query.order,
        filters: query.filters,
      }),
      offset: pageNum > 0 ? pageNum * 100 : undefined,
      limit: 100,
    });
    next = val.next;
    if (pageNum === 0) {
      result = { ...val };
    } else {
      result.next = next;
      result.jobs = [...result.jobs, ...val.jobs];
    }
  }
  return result;
};

export const PaginatedReflectionJobsResource = new SmartResource(
  paginatedReflectionJobsFetcher,
  {
    mode: "takeEvery",
  },
);

export const ReflectionJobsPollingResource = new PollingResource(
  ({ reflectionId }) => {
    return listReflectionJobs({
      reflectionId,
      ...formatJobsBackendQuery({
        sort: "st",
        order: "DESCENDING",
        filters: {},
      }),
      offset: undefined,
      limit: 100,
    });
  },
  { pollingInterval: 5000 },
);
