import jenkins.model.Jenkins;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.FilePath;
import hudson.plugins.s3.S3BucketPublisher;
import hudson.Util;

import groovy.json.JsonOutput;
import groovy.json.JsonBuilder;
//import groovy.json.StreamingJsonBuilder;


/*
def whereTo = manager.build.getBuildVariables().get('JOB_TO_ANALYZE')

def job = Jenkins.instance.getItemByFullName(whereTo.toString())

def buildToAnalyze = null
if (!manager.build.getBuildVariables().get('JOB_TO_ANALYZE_NUMBER'))
  buildToAnalyze = job.getLastBuild()
else
  buildToAnalyze = job.getBuildByNumber(manager.build.getBuildVariables().get('JOB_TO_ANALYZE_NUMBER').toInteger())
*/
def buildToAnalyze = manager.build

// map-based implementation
/*def buildMetadata = [:]
buildMetadata['name'] = buildToAnalyze.getProject().getFullName()
buildMetadata['number'] = buildToAnalyze.getNumber()
buildMetadata['description'] = buildToAnalyze.getDescription()
buildMetadata['duration'] = buildToAnalyze.getDuration()
buildMetadata['causes'] = buildToAnalyze.getCauses()
buildMetadata['result'] = buildToAnalyze.getResult().toString()*/

def getChangeSetsDetails(changes, parameters, env) {
  def to_return = []
  def tmp = [:]
  if (parameters.get('GERRIT_CHANGE_ID')) {
    tmp['id'] = parameters.get('GERRIT_CHANGE_ID')
    tmp['revision'] = parameters.get('GERRIT_PATCHSET_REVISION')
    tmp['repository'] = parameters.get('GERRIT_PROJECT')
    tmp['kind'] = parameters.get('GERRIT_EVENT_TYPE')
    to_return.add(tmp)
  }
  else {
    def regex = /.*(Change-Id: )(I[a-z0-9]{40}).*/
    changes.each { change ->
    def items = change.getItems()
    items.each {
        def findChangeId = (it.getMsg() =~ regex)
        // TODO: handles exceptions instead of checks?
        tmp['id'] = findChangeId.count !=0 ? findChangeId[0][2] : null
        tmp['revision'] = it.getRevision() ?: it.getCommitId()
        // TODO: improve the check on fields existance
        // NOTE: the current implementation supposes the VCS tool is Git, and that Jenkins Git plugin is installed
        tmp['repository'] = change.getKind() == 'repo' ? it.getServerPath() : env.get('GIT_URL')
        tmp['kind'] = change.getKind()
        to_return.add(tmp)
      }
    }    
  }
  return to_return
}

def getBlockingSubProjects(actions) {
  def to_return = []
  // get all parametrized trigger plugin actions
  def triggers = actions.findAll { it instanceof hudson.plugins.parameterizedtrigger.BuildInfoExporterAction }
  // if any
  // TODO: handles exceptions instead of checks?
  if (triggers.size() != 0) {
    // for each, get the blocking builds
    triggers.each { action ->
      // for each blocking build, get name and number
      action.getTriggeredBuilds().each {
        def tmp = [:]
        tmp['name'] = it.getProject().getFullName()
        tmp['number'] = it.getNumber()
        to_return.add(tmp)
      }
    }
  }
  return to_return
}

def getNonBlockingSubProjects(actions) {
  def to_return = []
  // get all parametrized trigger plugin actions
  def triggers = actions.findAll { it instanceof hudson.plugins.parameterizedtrigger.BuildInfoExporterAction }
  // if any
  // TODO: handles exceptions instead of checks?
  if (triggers.size() != 0) {
    // for each, get the non blocking builds
    triggers.each { action ->
      // for each non blocking build, get name
      action.getTriggeredProjects().each {
        def tmp = [:]
        tmp['name'] = it.getFullName()
        // TODO: it seems the number is not available in the plugin internals for non-blocking builds
        //tmp['number'] = it.getNumber()
        to_return.add(tmp)
      }
    }
  }
  return to_return
}

def getS3Artifacts(action) {
  def to_return = []
  // TODO: handles exceptions instead of checks?
  if (action) {
    action.getArtifacts().each {
        to_return.add(it.getName())
    }
  }
  return to_return
}

// TODO: designed for junit only for the moment. Extend it for other plugins if needed.
def getTestResult(actions) {
  def to_return = []
  // get all test actions
  def testPublishers = actions.findAll { it instanceof AbstractTestResultAction }
  // TODO: handles exceptions instead of checks?
  if (testPublishers.size() != 0) {
    testPublishers.each {
      def tmp = [:]
      tmp['name'] = it.getResult().getName()
      tmp['duration'] = it.getResult().getDuration()
      tmp['pass'] = it.getResult().getPassCount()
      tmp['fail'] = it.getResult().getFailCount()
      tmp['skip'] = it.getResult().getSkipCount()
      
      to_return.add(tmp)
    }
  }
  return to_return
}


// alternative: use a pure JSON representation
// http://docs.groovy-lang.org/latest/html/documentation/core-domain-specific-languages.html#_jsonbuilder

// If you do not need to modify the structure and want a more memory-efficient approach, use StreamingJsonBuilder.
//StringWriter writer = new StringWriter()
//StreamingJsonBuilder builder = new StreamingJsonBuilder(writer)

def buildMetadata = new JsonBuilder()

buildMetadata {
  name buildToAnalyze.getProject().getFullName()
  
  number buildToAnalyze.getNumber()
  
  result buildToAnalyze.getResult().toString()
  
  description buildToAnalyze.getDescription()
  
  duration buildToAnalyze.getDuration()
  
  timestamp buildToAnalyze.getTimestamp()
  
  // NOTE: the time spent in queue requires the Metrics plugin to be installed
  // TODO: add some checks, otherwise exceptions are raised if the plugin is not available
  queuing buildToAnalyze.getAction(jenkins.metrics.impl.TimeInQueueAction.class).getQueuingDurationMillis()
  
  // see https://stackoverflow.com/questions/13992751/how-do-i-use-groovy-jsonbuilder-with-each-to-create-an-array
  causes buildToAnalyze.getCauses().collect { cause ->
    ['cause': cause.getShortDescription()]
  }
  
  // TODO: add the node IP
  // https://stackoverflow.com/questions/14930329/finding-ip-of-a-jenkins-node/14930330#14930330
  // NOTE: the Node object could be null, because it could be terminated (AWS instances): no IP in this case
  // how IPs are managed in AWS? If they are re-used, is this a relevant info to save?
  node buildToAnalyze.getBuiltOnStr()
  
  parameters buildToAnalyze.getBuildVariables()
  
  // TODO: this step currently support repo changes, not all other SCM plugins
  changes getChangeSetsDetails(buildToAnalyze.getChangeSets(),  buildToAnalyze.getBuildVariables(), buildToAnalyze.getEnvironment(manager.listener))
  
  dowstream buildToAnalyze.getDownstreamBuilds()
  
  upstream buildToAnalyze.getUpstreamBuilds()
  
  // TODO: can't put nested subprojects, using dedicated methods...stackoverflow errors
  /*subprojects {
    blocking getSubProjects(buildToAnalyze.getAction(hudson.plugins.parameterizedtrigger.BuildInfoExporterAction.class)) 
    //nonblocking buildToAnalyze.getAction(hudson.plugins.parameterizedtrigger.BuildInfoExporterAction.class).getTriggeredProjects().collect {
    //  ['name': it.getProject().getFullName(), 'number': it.getNumber()]
    //}
  }*/
  
  blocking getBlockingSubProjects(buildToAnalyze.getAllActions())
  
  nonblocking getNonBlockingSubProjects(buildToAnalyze.getAllActions())
    //(hudson.plugins.parameterizedtrigger.BuildInfoExporterAction.class))
  
  // TODO: can't put nested subprojects, using dedicated methods...multiple entries returned
  /*artifacts {
    jenkins buildToAnalyze.getArtifacts().collect { 
      it.toString() 
    }
    s3 getS3Artifacts(buildToAnalyze.getAction(hudson.plugins.s3.S3ArtifactsAction.class))
    
  }*/
  artifacts buildToAnalyze.getArtifacts().collect { 
    it.toString() 
  }
  s3artifacts getS3Artifacts(buildToAnalyze.getAction(hudson.plugins.s3.S3ArtifactsAction.class))
  
  testresult getTestResult(buildToAnalyze.getAllActions())
  
}


// see https://gist.github.com/esycat/6410360
//def json = toJson(buildMetadata)
//println(prettyPrint(json))

// print to the console log
//manager.listener.logger.println(JsonOutput.prettyPrint(buildMetadata.toString()))

// print to a file
def expanded = Util.replaceMacro('metadata.json', manager.build.getEnvironment(manager.listener))

def metadata = new FilePath(manager.build.getWorkspace(), expanded)
metadata.write(JsonOutput.prettyPrint(buildMetadata.toString()), null)

// upload the file to AWS S3
// try to use the S3 plugin, as it is already using the AWS S3 SDK
try {
  // S3 plugin initialization and related data to upload files
  def profile = S3BucketPublisher.getProfile()
  def bucket = Util.replaceMacro('${AWS_S3_BUCKET}', manager.build.getEnvironment(manager.listener));
  def storageClass = Util.replaceMacro('STANDARD', manager.build.getEnvironment(manager.listener));
  def region = 'us-east-1'
  int searchPathLength = (metadata.getParent().getRemote().length()) + 1
  
  // call the S3 plugin method
  def record = profile.upload(manager.build, manager.listener, bucket, metadata, searchPathLength, [], storageClass, region, false, true, true, false)

  manager.listener.logger.println("[Build metadata action] Uploaded " + record.getName() + " to bucket " + record.getBucket() + " region " + region)
  manager.listener.logger.println("[Build metadata action] URL: " + profile.getDownloadURL(manager.build, record))
} 
catch (IOException e) {
  e.printStackTrace(manager.listener.error("[Build metadata action] Failed to upload build metadata file(s) to S3"));
  //build.setResult(Result.UNSTABLE);
}
