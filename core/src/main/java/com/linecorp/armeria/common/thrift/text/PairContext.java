// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package com.linecorp.armeria.common.thrift.text;

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * A map parsing context that tracks if we are parsing a key, which
 * is on the left hand side of the ":" operator, or a value.
 * Json mandates that keys are strings
 * e.g.
 * {
 * "1" : 1,
 * "2" : 2,
 * }
 * Note the required quotes on the lhs.
 * We maintain an iterator over all of our child name/value pairs, and
 * a pointer to the current one being parsed.
 *
 * @author Alex Roetter
 */
class PairContext extends BaseContext {

    private final Iterator<Map.Entry<String, JsonNode>> children;
    private boolean lhs;
    private Map.Entry<String, JsonNode> currentChild;

    /**
     * Creates an iterator over this object's children.
     */
    protected PairContext(JsonNode json) {
        children = null != json ? json.fields() : null;
    }

    @Override
    protected void write() {
        lhs = !lhs;
    }

    @Override
    protected void read() {
        lhs = !lhs;
        // every other time, do a read, since the read gets the name & value
        // at once.
        if (isLhs()) {
            if (!children.hasNext()) {
                throw new RuntimeException(
                        "Called PairContext.read() too many times!");
            }
            currentChild = children.next();
        }
    }

    @Override
    protected JsonNode getCurrentChild() {
        if (lhs) {
            return new TextNode(currentChild.getKey());
        }
        return currentChild.getValue();
    }

    @Override
    protected boolean hasMoreChildren() {
        return children.hasNext();
    }

    protected boolean isLhs() {
        return lhs;
    }
}
