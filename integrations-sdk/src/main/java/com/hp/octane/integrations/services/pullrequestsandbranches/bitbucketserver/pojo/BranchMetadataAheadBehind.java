/*
 *     Copyright 2017 EntIT Software LLC, a Micro Focus company, L.P.
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package com.hp.octane.integrations.services.pullrequestsandbranches.bitbucketserver.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchMetadataAheadBehind {

    private long ahead;
    private long behind;

    public long getAhead() {
        return ahead;
    }

    public void setAhead(long ahead) {
        this.ahead = ahead;
    }

    public long getBehind() {
        return behind;
    }

    public void setBehind(long behind) {
        this.behind = behind;
    }
}
