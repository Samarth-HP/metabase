/* eslint-disable react/prop-types */
import React, { Component } from "react";
import PropTypes from "prop-types";
import Button from "metabase/core/components/Button/Button.tsx";
import Input from "metabase/core/components/Input/Input.tsx";
import Select, { Option } from "metabase/core/components/Select/Select.jsx";

class BigQueryConditions extends Component {
  constructor(props) {
    super(props);
    const { value } = props;
    const rules = [];
    const mode = value && value.mode ? value.mode : "CUSTOM";
    if (value && value.rules) {
      value.rules.forEach(r => {
        let found = false;
        if (isNaN(r.start) && mode === "CUSTOM") {
          found = true;
        }
        if (isNaN(r.end) && mode === "CUSTOM") {
          found = true;
        }
        if (parseFloat(r.start) > parseFloat(r.end)) {
          found = true;
        }
        if (!/^#[0-9A-F]{6}$/i.test(r.color)) {
          found = true;
        }

        if (!found) {
          rules.push(r);
        }
      });
    }
    rules.push({ condition: "Is Equal to", condition2: "Is Less than" });
    this.state = {
      rules,
      mode, // RANGE, COLOR, CUSTOM
    };
  }

  static propTypes = {
    onChange: PropTypes.func.isRequired,
    size: PropTypes.number,
    triggerSize: PropTypes.number,
    value: PropTypes.string,
  };

  addCondition = () => {
    const { rules, mode } = this.state;
    const last = rules[rules.length - 1];

    if (isNaN(last.value) && mode === "CUSTOM") {
      alert("Invalid end");
      return;
    }
    if (!/^#[0-9A-F]{6}$/i.test(last.color)) {
      alert("Invalid Color");
      return;
    }

    this.setState(
      {
        rules: [
          ...rules,
          { condition: "Is Equal to", condition2: "Is Less than" },
        ],
      },
      () => {
        this.props.onChange({ rules: this.state.rules, mode: this.state.mode });
      },
    );
  };
  removeCondition = index => {
    const { rules } = this.state;
    rules.splice(index, 1);
    rules[rules.length - 1].color = "";
    rules[rules.length - 1].value = "";
    this.setState({ rules }, () => {
      this.props.onChange({ rules: this.state.rules });
    });
  };
  render() {
    const { rules } = this.state;
    return (
      <div className="region-condition-map-container">
        <div style={{ padding: "5px" }}></div>
        {rules.map((rule, index) => {
          return (
            <div key={index}>
              <div>
                {
                  <div>
                    <div
                      style={{
                        display: "flex",
                        flexDirection: "column",
                        paddingBottom: "10px",
                      }}
                    >
                      <Select
                        value={rule.condition}
                        onChange={e => {
                          console.log(e.target.value);
                          rules[index].condition = e.target.value;
                          this.setState(
                            { rules: JSON.parse(JSON.stringify(rules)) },
                            () => {},
                          );
                        }}
                      >
                        {[
                          "Is Equal to",
                          "Is Less than",
                          "Is Greater than",
                          "Is Less than Equal to",
                          "Is Greater than Equal to",
                        ].map(item => {
                          return (
                            <Option key={item} value={item}>
                              {item}
                            </Option>
                          );
                        })}
                      </Select>
                      <div style={{ padding: "5px" }}></div>
                      <div style={{ flexGrow: 1 }}>
                        <Input
                          value={rule.value}
                          width={"100%"}
                          onChange={e => {
                            rules[index].value = e.target.value;
                            this.setState(
                              { rules: JSON.parse(JSON.stringify(rules)) },
                              () => {},
                            );
                          }}
                          type={"number"}
                          placeholder={"Value"}
                        />
                      </div>
                    </div>
                    {rule.condition != "Is Equal to" && (
                      <div
                        style={{
                          display: "flex",
                          flexDirection: "column",
                          paddingBottom: "10px",
                        }}
                      >
                        <Select
                          value={rule.condition2}
                          onChange={e => {
                            console.log(e.target.value);
                            rules[index].condition2 = e.target.value;
                            this.setState(
                              { rules: JSON.parse(JSON.stringify(rules)) },
                              () => {},
                            );
                          }}
                        >
                          {[
                            "Is Less than",
                            "Is Greater than",
                            "Is Less than Equal to",
                            "Is Greater than Equal to",
                          ].map(item => {
                            return (
                              <Option key={item} value={item}>
                                {item}
                              </Option>
                            );
                          })}
                        </Select>
                        <div style={{ padding: "5px" }}></div>
                        <div style={{ flexGrow: 1 }}>
                          <Input
                            value={rule.value2}
                            width={"100%"}
                            onChange={e => {
                              rules[index].value2 = e.target.value;
                              this.setState(
                                { rules: JSON.parse(JSON.stringify(rules)) },
                                () => {},
                              );
                            }}
                            type={"number"}
                            placeholder={"Value"}
                          />
                        </div>
                      </div>
                    )}
                  </div>
                }
                <Input
                  value={rule.color}
                  width={"100%"}
                  type={"text"}
                  onChange={e => {
                    rules[index].color = e.target.value;
                    this.setState(
                      { rules: JSON.parse(JSON.stringify(rules)) },
                      () => {},
                    );
                  }}
                  placeholder={"Color in HEX (#000000)"}
                />

                <div style={{ padding: "5px" }}></div>
                {index === rules.length - 1 ? (
                  <div>
                    <Button mr={1} onClick={this.addCondition}>
                      Add Condition
                    </Button>
                  </div>
                ) : (
                  <Button
                    mr={1}
                    variant="danger"
                    onClick={() => this.removeCondition(index)}
                  >
                    Remove Condition
                  </Button>
                )}
                <div style={{ padding: "10px" }}></div>
              </div>
            </div>
          );
        })}
      </div>
    );
  }
}

export default BigQueryConditions;
