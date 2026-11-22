package com.elmacore.agentplatform.execution.application;
import com.elmacore.agentplatform.execution.domain.*;
import com.elmacore.agentplatform.knowledge.application.KnowledgeStore;
import java.util.*;
/** Shared authorized evidence selection and source validation; never grants document access. */
public final class GroundedAnswer {
 private GroundedAnswer(){}
 public record Context(List<ExecutionRuntime.Passage> passages,Map<String,RunResult.Citation> sources){}
 public static Context prepare(List<KnowledgeStore.Match> matches){
  var passages=new ArrayList<ExecutionRuntime.Passage>();var sources=new LinkedHashMap<String,RunResult.Citation>();int remaining=8000;
  for(var match:matches){
   if(passages.size()==6||remaining==0)break;
   String text=match.text();int count=Math.min(remaining,text.codePointCount(0,text.length()));
   text=text.substring(0,text.offsetByCodePoints(0,count));remaining-=count;String source="S"+(passages.size()+1);
   passages.add(new ExecutionRuntime.Passage(source,text));sources.put(source,new RunResult.Citation(match.documentId(),match.chunkId(),match.filename(),match.sourcePage()));
  }
  return new Context(List.copyOf(passages),Collections.unmodifiableMap(sources));
 }
 public static RunResult result(UUID run,UUID agent,long duration,ExecutionRuntime.Response output,Context context){
  try{validate(output,context.sources().keySet());}
  catch(RunFailure invalid){
   if(output!=null&&ExecutionRuntime.MODEL.equals(output.modelId())&&output.usage()!=null&&!invalidUsage(output.usage().inputTokens(),1000000)&&!invalidUsage(output.usage().outputTokens(),800))throw new RunFailure(invalid.code(),invalid.status(),run,output.modelId(),output.usage());
   throw invalid;
  }
  return new RunResult(run,agent,"SUCCEEDED",output.answer(),output.sourceIds().stream().distinct().map(context.sources()::get).toList(),output.usage(),duration,null,output.modelId());
 }
    public static void validate(ExecutionRuntime.Response output,Set<String> allowed) {
        if(output==null || output.answer()==null || output.answer().isBlank() || output.answer().codePointCount(0,output.answer().length())>6000
                || output.answer().indexOf(0)>=0 || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(output.answer())
                || output.sourceIds()==null || output.sourceIds().isEmpty() || output.sourceIds().size()>6
                || output.sourceIds().stream().anyMatch(s->!allowed.contains(s)) || !ExecutionRuntime.MODEL.equals(output.modelId())
                || output.usage()==null || invalidUsage(output.usage().inputTokens(),1000000) || invalidUsage(output.usage().outputTokens(),800))
            throw new RunFailure("INVALID_MODEL_OUTPUT",502);
    }
    private static boolean invalidUsage(Integer n,int max){return n!=null && (n<0 || n>max);}
}
