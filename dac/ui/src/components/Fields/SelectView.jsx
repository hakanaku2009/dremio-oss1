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
import { PureComponent, createRef, Fragment } from "react";
import { Popover, MouseEvents } from "#oss/components/Popover";
import classNames from "clsx";

import PropTypes from "prop-types";
import { Tooltip } from "dremio-ui-lib";

import {
  disabled as disabledCls,
  open as openCls,
  select as selectCls,
  menuHeader as menuHeaderCls,
  disabledIcon as disabledIconCls,
} from "./SelectView.less";
/**
 * node or render props function
 * renderProps: ({ openDD: func, closeDD: func, isOpen: func }) => {@see PropTypes.node}
 * {see SelectView#renderNodeOrProps} for details
 */
const nodeOrRenderProps = PropTypes.oneOfType([PropTypes.node, PropTypes.func]);
const styles = {
  arrow: {
    color: "#77818F",
    fontSize: "10px",
    paddingLeft: "7px",
  },
  arrowDisabled: {
    color: "var(--color--neutral--150)",
  },
};

export class SelectView extends PureComponent {
  static propTypes = {
    content: nodeOrRenderProps,
    children: nodeOrRenderProps,
    menuHeader: PropTypes.oneOfType([PropTypes.node, PropTypes.string]),
    /** If true, an icon with an arrow is hidden */
    hideExpandIcon: PropTypes.bool,
    disabled: PropTypes.bool,
    className: PropTypes.string,
    style: PropTypes.object,
    dataQa: PropTypes.string,
    // #region list properties ------------------------------------
    listClass: PropTypes.string,
    listStyle: PropTypes.object,
    /** set to true if you want to align a popover to the right side of the content */
    listRightAligned: PropTypes.bool,
    listWidthSameAsAnchorEl: PropTypes.bool,
    // #endregion list properties ---------------------------------
    beforeOpen: PropTypes.func,
    beforeClose: PropTypes.func,
    useLayerForClickAway: PropTypes.bool,
    /** Attributes that would be applied to a content wrapper dom element */
    rootAttrs: PropTypes.object,
    iconStyle: PropTypes.object,
    popoverFilters: PropTypes.string,
    iconClass: PropTypes.string,
    hasIconFirst: PropTypes.bool,
    closeOnSelect: PropTypes.bool,
    hasSpecialIcon: PropTypes.bool,
    iconId: PropTypes.string,
    iconTooltip: PropTypes.string,
    container: PropTypes.any,
  };

  static defaultProps = {
    useLayerForClickAway: true,
    icon: "fa fa-chevron-down",
    iconStyle: styles.arrow,
  };

  state = { anchorEl: null };
  contentRef = createRef();

  renderNodeOrProps = (nodeOrProps) => {
    if (typeof nodeOrProps === "function") {
      return nodeOrProps({
        openDD: this.openDD,
        closeDD: this.closeDD,
        isOpen: this.isOpen,
      });
    }
    return nodeOrProps;
  };

  openDD = (e) => {
    const { beforeOpen, disabled } = this.props;

    if (disabled) {
      return;
    }
    if (beforeOpen) {
      beforeOpen(e);
    }
    this.setState({
      anchorEl: this.contentRef.current,
    });
  };

  closeDD = () => {
    if (this.props.beforeClose) {
      this.props.beforeClose();
    }
    this.setState({
      anchorEl: null,
    });
  };

  isOpen = () => !this.props.disabled && Boolean(this.state.anchorEl);

  renderIcon = () => {
    const { disabled, hasSpecialIcon, iconId, iconClass, iconTooltip } =
      this.props;

    const icon = !hasSpecialIcon ? (
      <dremio-icon
        name="interface/caretDown"
        class={disabled ? disabledIconCls : ""}
      />
    ) : (
      <dremio-icon name={iconId} class={iconClass} />
    );

    return iconTooltip ? <Tooltip title={iconTooltip}>{icon}</Tooltip> : icon;
  };

  renderMenuHeader() {
    const { menuHeader } = this.props;
    return <div className={menuHeaderCls}>{menuHeader}</div>;
  }

  render() {
    const {
      content,
      children,
      className,
      dataQa,
      hideExpandIcon,
      disabled,
      listClass,
      style,
      listRightAligned,
      useLayerForClickAway,
      listWidthSameAsAnchorEl,
      listStyle,
      rootAttrs,
      popoverFilters,
      menuHeader,
      hasIconFirst,
      closeOnSelect,
    } = this.props;
    const { anchorEl } = this.state;
    const open = this.isOpen();
    return (
      <Fragment>
        <div
          onClick={open ? null : this.openDD}
          ref={this.contentRef}
          className={classNames({
            [selectCls]: true,
            [disabledCls]: disabled,
            [className]: true,
            [openCls]: open,
          })}
          style={style}
          data-qa={dataQa}
          tabIndex={disabled ? -1 : 0}
          onKeyDown={
            open
              ? null
              : (e) => {
                  if (e.code === "Enter") {
                    this.openDD(e);
                  }
                }
          }
          {...rootAttrs}
        >
          {hasIconFirst && this.renderIcon()}
          {this.renderNodeOrProps(content)}
          {!hideExpandIcon && !hasIconFirst && this.renderIcon()}
        </div>
        <Popover
          anchorEl={open ? anchorEl : null}
          listRightAligned={listRightAligned}
          onClose={this.closeDD}
          dataQa="convertTo" // todo change that
          listStyle={listStyle}
          listClass={listClass}
          useLayerForClickAway={useLayerForClickAway}
          clickAwayMouseEvent={MouseEvents.onClick}
          listWidthSameAsAnchorEl={listWidthSameAsAnchorEl}
          popoverFilters={popoverFilters}
          container={this.props.container || document.body}
        >
          <div onClick={closeOnSelect && this.closeDD}>
            {menuHeader && this.renderMenuHeader()}
            {this.renderNodeOrProps(children)}
          </div>
        </Popover>
      </Fragment>
    );
  }
}
