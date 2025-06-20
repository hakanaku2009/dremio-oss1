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
import { bootIntercom } from "#oss/intercom/bootIntercom";

const APP_KEY = "globalApp";
const WIKI_SIZE_KEY = "WIKI_SIZE";

const emptyApp = {
  home: {
    recentDatasetsToken: "",
    readonlyLinks: "",
    pinnedItems: {},
  },
  explore: {
    tasks: [],
    transform: {
      info: {},
    },
  },
  customData: {},
  SSOLogin: "SSO",
};

export function useProjectContext() {
  return null;
}

export class LocalStorageUtils {
  constructor() {
    const app = this.getApp();
    if (!app) {
      this._safeSave(APP_KEY, emptyApp);
    }
  }

  renderSSOLoginScreen() {
    return localStorage.getItem("SSOLogin");
  }

  setSSOLoginChoice() {
    localStorage.getItem("SSOLogin") === "SSO"
      ? localStorage.setItem("SSOLogin", "nameAndPassword")
      : localStorage.setItem("SSOLogin", "SSO");
  }

  getUserData() {
    // todo: no fake objects: return null if no user
    try {
      const userData = JSON.parse(localStorage.getItem("user"));
      if (typeof userData !== "object") {
        return {};
      }
      return userData;
    } catch (e) {
      return {};
    }
  }

  setUserData(user) {
    localStorage.setItem("user", JSON.stringify(user));
    try {
      bootIntercom();
    } catch (e) {
      //
    }
  }

  clearUserData() {
    localStorage.removeItem("rs");
    return localStorage.removeItem("user");
  }

  getAuthToken() {
    const token = this.getUserData() && this.getUserData().token;

    return token ? `_dremio${token}` : null;
  }

  getUserId() {
    const user = this.getUserData();

    return user ? user.userId : null;
  }

  isUserAnAdmin() {
    const isAdmin = this.getUserData();
    return isAdmin !== null ? isAdmin.admin : null;
  }

  getUserPermissions() {
    const user = this.getUserData();
    return user?.permissions;
  }

  getApp() {
    return this._safeParse(localStorage.getItem(APP_KEY));
  }

  setApp(app) {
    this._safeSave(APP_KEY, app);
  }

  getPinnedItems() {
    const app = this.getApp();
    return app.home.pinnedItems || {};
  }

  updatePinnedItemState(state) {
    const app = this.getApp();
    app.home.pinnedItems = state;
    this.setApp(app);
  }

  setCustomValue(key, value) {
    const app = this.getApp();
    app.customData[key] = value;
    this.setApp(app);
  }

  getCustomValue(key) {
    const app = this.getApp();
    return app.customData[key];
  }

  getGridTasks(now) {
    const app = this.getApp();
    const SECONDS = 1000;
    const LIFE_TIME = 100000;
    const filteredTasks = app.explore.tasks.filter((task) => {
      return Math.abs((now - task.timestamp) / SECONDS) < LIFE_TIME;
    });
    this.setGridTasks(filteredTasks);
    return filteredTasks;
  }

  setGridTasks(tasks) {
    const app = this.getApp();
    app.explore.tasks = tasks;
    this.setApp(app);
  }

  setDefaultSqlState(sqlState) {
    this._safeSave("sqlState", sqlState);
  }

  getDefaultSqlState() {
    const sqlState = localStorage.getItem("sqlState");
    if (sqlState == null) {
      this.setDefaultSqlState(true);
      return true;
    }
    return localStorage.getItem("sqlState") === "true";
  }

  setWikiVisibleState(isWikiVisible) {
    this._safeSave("isWikiVisible", isWikiVisible);
  }

  getWikiVisibleState() {
    return localStorage.getItem("isWikiVisible") === "true"; // false is default value
  }

  getWikiSize() {
    return localStorage.getItem(WIKI_SIZE_KEY);
  }

  setWikiSize(wikiSize) {
    this._safeSave(WIKI_SIZE_KEY, wikiSize);
  }

  setDefaultSqlHeight(sqlHeight) {
    this._safeSave("sqlHeight", sqlHeight);
  }

  getDefaultSqlHeight() {
    return +localStorage.getItem("sqlHeight") || 0;
  }

  _safeParse(data) {
    try {
      return JSON.parse(data) || emptyApp;
    } catch (err) {
      console.error("INVALID DATA TO PARSE");
      return emptyApp;
    }
  }

  _safeSave(key, data) {
    localStorage.setItem(key, JSON.stringify(data));
  }

  setTransformValue(values) {
    const { columnType, toType, ...restValues } = values;
    const app = this.getApp();
    const transform = app.transform || {};

    if (!transform[columnType]) {
      transform[columnType] = {};
    }
    transform[columnType][toType] = restValues;
    app.transform = transform;
    this.setApp(app);
  }

  getTransformValue(columnType, toType) {
    const app = this.getApp();
    return app.transform &&
      app.transform[columnType] &&
      app.transform[columnType][toType]
      ? app.transform[columnType][toType]
      : {};
  }

  getInstanceId() {
    return sessionStorage.getItem("instanceId");
  }

  setJobColumns(columns) {
    this._safeSave("columns", columns);
  }

  getJobColumns() {
    return (
      localStorage.getItem("columns") &&
      JSON.parse(localStorage.getItem("columns"))
    );
  }

  clearJobColumns() {
    localStorage.removeItem("columns");
  }

  setSqlAutocomplete(theme) {
    this._safeSave("isAutocomplete", theme);
  }

  getSqlAutocomplete() {
    // to show icon or not is handled by support flag so we can make this true by default
    if (localStorage.getItem("isAutocomplete") === null) {
      return true;
    }
    return (
      localStorage.getItem("isAutocomplete") &&
      JSON.parse(localStorage.getItem("isAutocomplete"))
    );
  }

  isSideNavWide() {
    const sideNavWide = localStorage.getItem("sideNavWide");
    if (sideNavWide === null) {
      return false;
    }

    return sideNavWide === "true";
  }

  setSideNavWide(isWide) {
    localStorage.setItem("sideNavWide", isWide);
  }

  isQueryEngine() {
    return false;
  }

  setProjectSwitcherState(state) {
    localStorage.setItem("projectSwitcherState", state);
  }

  getProjectSwitcherState() {
    return localStorage.getItem("projectSwitcherState");
  }

  setJobsFilters(state) {
    localStorage.setItem("jobsFilters", state);
  }

  getJobsFiltersState() {
    return localStorage.getItem("jobsFilters");
  }

  setJobsDateTimeFilter(state) {
    localStorage.setItem("jobsFiltersDateTime", state);
  }

  getJobsDateTimeFilter() {
    return localStorage.getItem("jobsFiltersDateTime");
  }
}

// todo: this shouldn't export `undefined` - it should use an in-memory only store
const localStorageUtils =
  typeof localStorage !== "undefined" ? new LocalStorageUtils() : undefined;

export default localStorageUtils;
