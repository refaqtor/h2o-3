package ai.h2o.automl.preprocessing;

import ai.h2o.automl.AutoML;
import ai.h2o.automl.AutoMLBuildSpec;
import ai.h2o.automl.PreprocessingStep;
import ai.h2o.automl.targetencoder.TargetEncodingHyperparamsEvaluator;
import ai.h2o.automl.targetencoder.strategy.GridSearchModelParametersSelectionStrategy;
import ai.h2o.automl.targetencoder.strategy.ModelParametersSelectionStrategy;
import ai.h2o.automl.targetencoder.strategy.ModelValidationMode;
import ai.h2o.targetencoding.TargetEncoderModel;
import ai.h2o.targetencoding.strategy.AllCategoricalTEApplicationStrategy;
import ai.h2o.targetencoding.strategy.TEApplicationStrategy;
import hex.ModelBuilder;
import water.fvec.Frame;
import water.util.Log;
import water.util.StringUtils;

import java.util.Optional;

public class TEPreprocessingStep extends PreprocessingStep<TargetEncoderModel> {

  ModelParametersSelectionStrategy<TargetEncoderModel.TargetEncoderParameters> _modelParametersSelectionStrategy = null;

  public TEPreprocessingStep(Preprocessor preprocessor, AutoML autoML) {
    super(preprocessor, autoML);
  }

  @Override
  protected void applyIfUseful(ModelBuilder modelBuilder, double baseLineLoss) {

    Optional<ModelParametersSelectionStrategy.Evaluated> bestTEParamsOpt = findBestPreprocessingParams(modelBuilder);

    if(bestTEParamsOpt.isPresent()) {
      ModelParametersSelectionStrategy.Evaluated evaluatedBest = bestTEParamsOpt.get();
      boolean scoreIsBetterThanBaseline = evaluatedBest.getScore() < baseLineLoss;

      if (scoreIsBetterThanBaseline) {
//        pipelineModelBuilder.addPreprocessorModel(evaluatedBest.getModel()._key); //TODO here we should add te model to original modelBuilder( this ability is coming from separate PR)

        _modelParametersSelectionStrategy.removeAllButBest();
      } else {
        _modelParametersSelectionStrategy.removeAll();
      }
    }
  }

  //TODO probably better to move most of the logic into a constructor
  private Optional<ModelParametersSelectionStrategy.Evaluated> findBestPreprocessingParams(ModelBuilder modelBuilder) {
    AutoMLBuildSpec.AutoMLTEControl te_spec = _aml.getBuildSpec().te_spec;
    Frame leaderboardFrame = _aml.getLeaderboardFrame();
    String responseColumnName = modelBuilder._parms._response_column;
    ModelValidationMode validationMode = _aml.getValidationFrame() == null ? ModelValidationMode.CV : ModelValidationMode.VALIDATION_FRAME; // mode could be just boolean

    return selectColumnsForEncoding(te_spec.application_strategy, modelBuilder._parms.train(), responseColumnName)
            .map(columnsToEncode -> {
              TargetEncodingHyperparamsEvaluator evaluator = new TargetEncodingHyperparamsEvaluator();

              _modelParametersSelectionStrategy = new GridSearchModelParametersSelectionStrategy(modelBuilder, te_spec, leaderboardFrame, columnsToEncode, validationMode, evaluator);

              ModelParametersSelectionStrategy.Evaluated<TargetEncoderModel> bestEvaluated = _modelParametersSelectionStrategy.getBestParamsWithEvaluation();
              Log.info("Best TE parameters for chosen columns " + StringUtils.join(",", columnsToEncode) + " were selected to be: " + bestEvaluated.getParams());
              return bestEvaluated;
            });
  }

  private Optional<String[]> selectColumnsForEncoding(TEApplicationStrategy applicationStrategy, Frame trainingFrame, String responseColumnName) {
    TEApplicationStrategy effectiveApplicationStrategy = applicationStrategy != null ? applicationStrategy : new AllCategoricalTEApplicationStrategy(trainingFrame, new String[]{responseColumnName});
    String[] columnsToEncode = effectiveApplicationStrategy.getColumnsToEncode();
    return columnsToEncode.length == 0 ? Optional.empty() : Optional.of(columnsToEncode);
  }
}