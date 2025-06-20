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
package com.dremio.exec.ops;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.catalog.CatalogIdentity;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.acceleration.RelWithInfo;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionUtils;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptTable.ToRelContext;
import org.apache.calcite.rel.RelNode;

/**
 * Contains context information about view expansion(s) in a query. Part of {@link
 * com.dremio.exec.ops .QueryContext}. Before expanding a view into its definition, as part of the
 * {@link com.dremio.exec.planner.logical.ViewTable#toRel(ToRelContext, RelOptTable)}, first a
 * {@link ViewExpansionToken} is requested from ViewExpansionContext through {@link
 * #reserveViewExpansionToken(CatalogIdentity)}. Once view expansion is complete, a token is
 * released through {@link ViewExpansionToken#release()}. A view definition itself may contain zero
 * or more views for expanding those nested views also a token is obtained.
 *
 * <p>Ex: Following are the available view tables: { "view_1", "view_2", "view_3", "view_4" }.
 * Corresponding owners are {"view1Owner", "view2Owner", "view3Owner", "view4Owner"}. Definition of
 * "view4" : "SELECT field4 FROM view3" Definition of "view3" : "SELECT field4, field3 FROM view2"
 * Definition of "view2" : "SELECT field4, field3, field2 FROM view1" Definition of "view1" :
 * "SELECT field4, field3, field2, field1 FROM someTable"
 *
 * <p>Query is: "SELECT * FROM view4". Steps: 1. "view4" comes for expanding it into its definition
 * 2. A token "view4Token" is requested through {@link #reserveViewExpansionToken(CatalogIdentity
 * view4Owner)} 3. "view4" is called for expansion. As part of it 3.1 "view3" comes for expansion
 * 3.2 A token "view3Token" is requested through {@link #reserveViewExpansionToken(CatalogIdentity
 * view3Owner)} 3.3 "view3" is called for expansion. As part of it 3.3.1 "view2" comes for expansion
 * 3.3.2 A token "view2Token" is requested through {@link #reserveViewExpansionToken(CatalogIdentity
 * view2Owner)} 3.3.3 "view2" is called for expansion. As part of it 3.3.3.1 "view1" comes for
 * expansion 3.3.3.2 A token "view1Token" is requested through {@link
 * #reserveViewExpansionToken(CatalogIdentity view1Owner)} 3.3.3.3 "view1" is called for expansion
 * 3.3.3.4 "view1" expansion is complete 3.3.3.5 Token "view1Token" is released 3.3.4 "view2"
 * expansion is complete 3.3.5 Token "view2Token" is released 3.4 "view3" expansion is complete 3.5
 * Token "view3Token" is released 4. "view4" expansion is complete 5. Token "view4Token" is
 * released.
 */
public class ViewExpansionContext {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ViewExpansionContext.class);

  private final CatalogIdentity catalogIdentity;
  private final ObjectIntHashMap<CatalogIdentity> userTokens = new ObjectIntHashMap<>();

  private Map<SubstitutionUtils.VersionedPath, DefaultSubstitutionInfo> defaultSubstitutionInfos;

  public static String DEFAULT_RAW_TARGET = "default_raw_matching_target";

  public ViewExpansionContext(CatalogIdentity catalogIdentity) {
    super();

    this.catalogIdentity = catalogIdentity;
    this.defaultSubstitutionInfos = new HashMap<>();
  }

  /**
   * Reserve a token for expansion of view owned by given identity.
   *
   * @param viewOwner Identity who owns the view.
   * @return An instance of {@link com.dremio.exec.ops.ViewExpansionContext.ViewExpansionToken}
   *     which must be released when done using the token.
   */
  public ViewExpansionToken reserveViewExpansionToken(CatalogIdentity viewOwner) {
    int totalTokens = 1;
    if (!Objects.equals(catalogIdentity, viewOwner)) {
      // We want to track the tokens only if the "viewOwner" is not same as the "queryUser".
      if (userTokens.containsKey(viewOwner)) {
        totalTokens += userTokens.get(viewOwner);
      }

      userTokens.put(viewOwner, totalTokens);

      logger.debug("Issued view expansion token for user '{}'", viewOwner);
    }

    return new ViewExpansionToken(viewOwner);
  }

  private void releaseViewExpansionToken(ViewExpansionToken token) {
    final CatalogIdentity viewOwner = token.viewOwner;

    if (Objects.equals(catalogIdentity, viewOwner)) {
      // If the token owner and queryUser are same, no need to track the token release.
      return;
    }

    Preconditions.checkState(
        userTokens.containsKey(viewOwner),
        "Given user doesn't exist in User Token store. Make sure token for this user is obtained first.");

    final int userTokenCount = userTokens.get(viewOwner);
    if (userTokenCount == 1) {
      // Remove the user from collection, when there are no more tokens issued to the user.
      userTokens.remove(viewOwner);
    } else {
      userTokens.put(viewOwner, userTokenCount - 1);
    }
    logger.debug("Released view expansion token issued for user '{}'", viewOwner);
  }

  /** Represents token issued to a view owner for expanding the view. */
  public class ViewExpansionToken {
    private final CatalogIdentity viewOwner;

    private boolean released;

    ViewExpansionToken(CatalogIdentity viewOwner) {
      this.viewOwner = viewOwner;
    }

    /**
     * Release the token. Once released all method calls (except release) cause {@link
     * java.lang.IllegalStateException}.
     */
    public void release() {
      if (!released) {
        released = true;
        releaseViewExpansionToken(this);
      }
    }
  }

  public CatalogIdentity getQueryUser() {
    return catalogIdentity;
  }

  /** Tracks all the default raw reflection substitutions made during validation */
  public void addDefaultSubstitutionInfo(
      NamespaceKey path,
      TableVersionContext context,
      DremioMaterialization materialization,
      RelNode substitution,
      Duration elapsed,
      RelNode target) {
    defaultSubstitutionInfos.put(
        SubstitutionUtils.VersionedPath.of(path.getPathComponents(), context),
        new DefaultSubstitutionInfo(materialization, substitution, elapsed, target));
  }

  public void reportDRRSubstitution(
      ExpansionNode expansionNode,
      DremioMaterialization dremioMaterialization,
      AttemptObserver attemptObserver) {
    assert expansionNode.isDefault();

    DefaultSubstitutionInfo info =
        defaultSubstitutionInfos.get(
            SubstitutionUtils.VersionedPath.of(
                expansionNode.getPath().getPathComponents(), expansionNode.getVersionContext()));

    attemptObserver.planSubstituted(
        dremioMaterialization,
        ImmutableList.of(
            RelWithInfo.create(info.substitution, "default_raw_matching", info.elapsed)),
        RelWithInfo.create(info.target, DEFAULT_RAW_TARGET, Duration.ZERO),
        0,
        true);
  }

  private static final class DefaultSubstitutionInfo {
    private final DremioMaterialization materialization;
    private final RelNode substitution;
    private final Duration elapsed;
    private final RelNode target;

    private DefaultSubstitutionInfo(
        DremioMaterialization materialization,
        RelNode substitution,
        Duration elapsed,
        RelNode target) {
      this.materialization = materialization;
      this.substitution = substitution;
      this.elapsed = elapsed;
      this.target = target;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DefaultSubstitutionInfo that = (DefaultSubstitutionInfo) o;
      return materialization
          .getMaterializationId()
          .equals(that.materialization.getMaterializationId());
    }

    @Override
    public int hashCode() {
      return Objects.hash(materialization.getMaterializationId());
    }
  }
}
