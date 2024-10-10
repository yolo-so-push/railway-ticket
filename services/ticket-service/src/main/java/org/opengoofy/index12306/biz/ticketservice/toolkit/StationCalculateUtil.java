/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.toolkit;

import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;

import java.util.ArrayList;
import java.util.List;

public class StationCalculateUtil {

    /**
     * 计算出发站和终点站中间的站点（包含出发站和终点站）
     *
     * @param trainStationAllList     所有站点数据
     * @param departure 出发站
     * @param arrival   终点站
     * @return 出发站和终点站中间的站点（包含出发站和终点站）
     */
    public static List<RouteDTO> throughStation(List<String> trainStationAllList, String departure, String arrival) {
        List<RouteDTO> res=new ArrayList<>();
        int startIndex = trainStationAllList.indexOf(departure);
        int endIndex = trainStationAllList.indexOf(arrival);
        if (startIndex<0||endIndex<0||startIndex>=endIndex){
            return res;
        }
        for (int i = startIndex; i <endIndex; i++) {
            for (int j = i+1; j <=endIndex; j++) {
                String startStation = trainStationAllList.get(i);
                String endStation = trainStationAllList.get(j);
                RouteDTO routeDTO=new RouteDTO(startStation,endStation);
                res.add(routeDTO);
            }
        }
        return res;
    }
    /**
     * 计算出发站和终点站需要扣减余票的站点（包含出发站和终点站）
     *
     * @param trainStationAllList     所有站点数据
     * @param departure 出发站
     * @param arrival   终点站
     * @return 出发站和终点站需要扣减余票的站点（包含出发站和终点站）
     */
    public static List<RouteDTO> takeoutStation(List<String> trainStationAllList, String departure, String arrival) {
        List<RouteDTO> res=new ArrayList<>();
        int startIndex = trainStationAllList.indexOf(departure);
        int endIndex = trainStationAllList.indexOf(arrival);
        if (startIndex<0||endIndex<0||startIndex>=endIndex){
            return res;
        }
        if (startIndex!=0){
            for (int i = 0; i <startIndex; i++) {
                for (int j = 1; j <trainStationAllList.size()-startIndex; j++) {
                    res.add(new RouteDTO(trainStationAllList.get(i),trainStationAllList.get(j+startIndex)));
                }
            }
        }
        for (int i = startIndex; i <=endIndex; i++) {
            for (int j = i+1; j <trainStationAllList.size()&&j<endIndex; j++) {
                res.add(new RouteDTO(trainStationAllList.get(i),trainStationAllList.get(j)));
            }
        }
        return res;
    }
}
