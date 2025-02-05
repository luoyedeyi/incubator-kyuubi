<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->

<template>
  <div class="editor">
    <el-space>
      <el-select v-model="param.engineType" :placeholder="$t('engine_type')">
        <el-option
          v-for="item in getEngineType()"
          :key="item"
          :label="item"
          :value="item" />
      </el-select>
      <el-button
        :disabled="!param.engineType || !editorVariables.content"
        :loading="resultLoading"
        type="primary"
        icon="VideoPlay"
        @click="handleQuerySql">
        {{ $t('operation.run') }}
      </el-button>
      <el-dropdown @command="handleChangeLimit">
        <span class="el-dropdown-link">
          Limit: {{ limit }}
          <el-icon class="el-icon--right">
            <arrow-down />
          </el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="l in limits" :key="l" :command="l">
              Limit: {{ l }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-space>
    <section>
      <MonacoEditor
        v-model="editorVariables.content"
        :language="editorVariables.language"
        :theme="theme"
        @editor-mounted="editorMounted"
        @change="handleContentChange"
        @editor-save="editorSave" />
    </section>
    <pre v-show="sqlLog" v-loading="logLoading">{{ sqlLog }}</pre>
    <el-tabs v-model="activeTab" type="card" class="result-el-tabs">
      <el-tab-pane
        v-loading="resultLoading"
        :label="`Result${sqlResult?.length ? ` (${sqlResult?.length})` : ''}`"
        name="result">
        <Result :data="sqlResult" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script lang="ts" setup>
  import MonacoEditor from '@/components/monaco-editor/index.vue'
  import Result from './Result.vue'
  import { ref, reactive, onUnmounted, toRaw } from 'vue'
  import type { Ref } from 'vue'
  import * as monaco from 'monaco-editor'
  import { format } from 'sql-formatter'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { getEngineType } from '@/utils/engine'
  import {
    openSession,
    closeSession,
    runSql,
    getSqlRowset,
    getSqlMetadata,
    log
  } from '@/api/editor'
  import type { IResponse, ISqlResult, IFields, ILog } from './types'

  const { t } = useI18n()
  const param = reactive({
    engineType: 'SPARK_SQL'
  })
  const limit = ref(10)
  const limits = ref([10, 50, 100])
  const sqlResult: Ref<any[] | null> = ref(null)
  const sqlLog = ref('')
  const activeTab = ref('result')
  const resultLoading = ref(false)
  const logLoading = ref(false)
  const sessionIdentifier = ref('')
  const theme = ref('customTheme')
  const editorVariables = reactive({
    editor: {} as any,
    language: 'sql',
    content: '',
    options: {}
  })

  const editorMounted = (editor: monaco.editor.IStandaloneCodeEditor) => {
    editorVariables.editor = editor
  }
  const handleFormat = () => {
    toRaw(editorVariables.editor).setValue(
      format(toRaw(editorVariables.editor).getValue())
    )
  }

  const editorSave = () => {
    handleFormat()
  }

  const handleContentChange = (value: string) => {
    editorVariables.content = value
  }

  const handleQuerySql = async () => {
    resultLoading.value = true
    logLoading.value = true
    const openSessionResponse: IResponse = await openSession({
      'kyuubi.engine.type': param.engineType
    }).catch(errorCatch)
    if (!openSessionResponse) return
    sessionIdentifier.value = openSessionResponse.identifier

    const runSqlResponse: IResponse = await runSql(
      {
        statement: editorVariables.content,
        runAsync: false
      },
      sessionIdentifier.value
    ).catch(errorCatch)
    if (!runSqlResponse) return

    Promise.all([
      getSqlRowset({
        operationHandleStr: runSqlResponse.identifier,
        fetchorientation: 'FETCH_NEXT',
        maxrows: limit.value
      }),
      getSqlMetadata({
        operationHandleStr: runSqlResponse.identifier
      })
    ])
      .then((result: any[]) => {
        sqlResult.value = result[0]?.rows?.map((row: IFields) => {
          const map: { [key: string]: any } = {}
          row.fields?.forEach(({ value }: ISqlResult, index: number) => {
            map[result[1].columns[index]?.columnName] = value
          })
          return map
        })
      })
      .catch(() => {
        sqlResult.value = []
      })
      .finally(() => {
        resultLoading.value = false
      })

    sqlLog.value = await log(runSqlResponse.identifier)
      .then((res: ILog) => {
        return res?.logRowSet?.join('\r\n')
      })
      .catch(() => '')
      .finally(() => {
        logLoading.value = false
      })
  }

  const errorCatch = (err: any) => {
    sqlResult.value = []
    sqlLog.value = err?.response?.data?.message || err?.message || ''
    ElMessage({
      message: t('message.run_failed'),
      type: 'error'
    })
    resultLoading.value = false
    logLoading.value = false
  }

  const handleChangeLimit = (command: number) => {
    limit.value = command
  }

  const customMonacoEditorTheme = () => {
    monaco.editor.defineTheme(theme.value, {
      base: 'vs',
      inherit: true,
      rules: [],
      colors: {
        'editor.foreground': '#000000',
        'editor.background': '#ffffff',
        'editor.lineHighlightBackground': '#f6f6f6',
        'editorGutter.background': '#e2e2e2'
      }
    })
    monaco.editor.setTheme(theme.value)
  }
  customMonacoEditorTheme()

  onUnmounted(() => {
    if (sessionIdentifier.value) {
      closeSession(sessionIdentifier.value)
    }
  })
</script>

<style lang="scss" scoped>
  .editor {
    > .el-space,
    > section,
    > pre,
    > .el-tabs {
      margin-bottom: 12px;
    }

    > .el-space {
      .el-select {
        width: 180px;
      }
      .el-button {
        width: 120px;
      }
      .el-dropdown {
        margin-left: 4px;
      }
    }

    > section {
      height: 180px;
      border: 1px solid #e0e0e0;
    }

    > pre {
      max-height: 120px;
      overflow: auto;
      border-left: 6px solid #e2e2e2;
      padding: 9.5px;
      margin: 24px 0;
      font-size: 12px;
      line-height: 20px;
      background-color: #f8f8f8;
      color: #444;
    }
  }
</style>
