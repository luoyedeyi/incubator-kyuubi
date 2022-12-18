/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createRouter, createWebHistory } from 'vue-router'
import overviewRoutes from './overview'
import sessionRoutes from './session'
import serverRoutes from './server'
import runSqlRoutes from './run-sql'

const routes = [
  {
    path: '/',
    name: 'main',
    redirect: {
      name: 'layout'
    }
  },
  {
    path: '/layout',
    name: 'layout',
    component: () => import('@/views/layout/index.vue'),
    redirect: 'overview',
    children: [
      ...overviewRoutes,
      ...sessionRoutes,
      ...serverRoutes,
      ...runSqlRoutes
    ]
  },
  { path: '/:catchAll(.*)', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
