import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestUtils
import io.opentracing.tag.Tags
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.env.Environment
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.node.Node
import org.elasticsearch.node.internal.InternalSettingsPreparer
import org.elasticsearch.transport.Netty3Plugin
import spock.lang.Shared

import static datadog.trace.agent.test.ListWriterAssert.assertTraces
import static org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING

class Elasticsearch5NodeClientTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.elasticsearch.enabled", "true")
  }

  static final int HTTP_PORT = TestUtils.randomOpenPort()
  static final int TCP_PORT = TestUtils.randomOpenPort()

  @Shared
  static Node testNode
  static File esWorkingDir

  def client = testNode.client()

  def setupSpec() {
    esWorkingDir = File.createTempFile("test-es-working-dir-", "")
    esWorkingDir.delete()
    esWorkingDir.mkdir()
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
      .put("http.port", HTTP_PORT)
      .put("transport.tcp.port", TCP_PORT)
      .put("transport.type", "netty3")
      .put("http.type", "netty3")
      .put(CLUSTER_NAME_SETTING.getKey(), "test-cluster")
      .build()
    testNode = new Node(new Environment(InternalSettingsPreparer.prepareSettings(settings)), [Netty3Plugin])
    testNode.start()
    TEST_WRITER.clear()
    testNode.client().admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(5000)
    TEST_WRITER.waitForTraces(1)
  }

  def cleanupSpec() {
    testNode?.close()
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch status"() {
    setup:
    def result = client.admin().cluster().health(new ClusterHealthRequest())

    def status = result.get().status

    expect:
    status.name() == "GREEN"

    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "ClusterHealthAction"
          operationName "elasticsearch.query"
          spanType null
          parent()
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "ClusterHealthAction"
            "elasticsearch.request" "ClusterHealthRequest"
            defaultTags()
          }
        }
      }
    }
  }

  def "test elasticsearch error"() {
    when:
    client.prepareGet(indexName, indexType, id).get()

    then:
    thrown IndexNotFoundException

    and:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType null
          errored true
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            errorTags IndexNotFoundException, "no such index"
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "invalid-index"
    indexType = "test-type"
    id = "1"
  }

  def "test elasticsearch get"() {
    setup:
    def indexResult = client.admin().indices().prepareCreate(indexName).get()

    expect:
    indexResult.acknowledged

    when:
    client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(5000)
    def emptyResult = client.prepareGet(indexName, indexType, id).get()

    then:
    !emptyResult.isExists()
    emptyResult.id == id
    emptyResult.type == indexType
    emptyResult.index == indexName

    when:
    def createResult = client.prepareIndex(indexName, indexType, id).setSource([:]).get()

    then:
    createResult.id == id
    createResult.type == indexType
    createResult.index == indexName
    createResult.status().status == 201

    when:
    def result = client.prepareGet(indexName, indexType, id).get()

    then:
    result.isExists()
    result.id == id
    result.type == indexType
    result.index == indexName

    and:
    assertTraces(TEST_WRITER, 6) {
      trace(0, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "CreateIndexAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "CreateIndexAction"
            "elasticsearch.request" "CreateIndexRequest"
            "elasticsearch.request.indices" indexName
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "ClusterHealthAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "ClusterHealthAction"
            "elasticsearch.request" "ClusterHealthRequest"
            defaultTags()
          }
        }
      }
      trace(2, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" indexType
            "elasticsearch.id" "1"
            "elasticsearch.version"(-1)
            defaultTags()
          }
        }
      }
      trace(3, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "PutMappingAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "PutMappingAction"
            "elasticsearch.request" "PutMappingRequest"
            defaultTags()
          }
        }
      }
      trace(4, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "IndexAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" indexType
            "elasticsearch.response.status" 201
            "elasticsearch.shard.replication.total" 2
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.request.description" "index {[test-index][test-type][1], source[{}]}"
            defaultTags()
          }
        }
      }
      trace(5, 1) {
        span(0) {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType null
          tags {
            "$Tags.COMPONENT.key" "elasticsearch-java"
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" indexType
            "elasticsearch.id" "1"
            "elasticsearch.version" 1
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "test-index"
    indexType = "test-type"
    id = "1"
  }
}