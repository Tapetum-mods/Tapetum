require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'
require_relative 'version-matrix'

class VersionMatrixTest < Minitest::Test
  def test_requested_versions_are_exact_and_ordered
    targets = TapetumVersionMatrix.targets(File.expand_path('../minecraft-versions.json', __dir__))
    assert_equal 35, targets.size
    assert_equal '1.16.5', targets.first
    assert_equal '26.3', targets.last
    refute_includes targets, '1.16.4'
    assert_equal targets.sort_by { |version| version.split('.').map(&:to_i) }, targets
  end

  def test_invalid_or_duplicate_targets_are_rejected
    Dir.mktmpdir do |directory|
      path = File.join(directory, 'versions.json')
      [['1.17', '1.17'], ['../1.17'], ['1.17*'], [17], {'version' => '1.17'}].each do |invalid|
        File.write(path, JSON.generate(invalid))
        assert_raises(ArgumentError) { TapetumVersionMatrix.targets(path) }
      end
    end
  end

  def test_non_repository_does_not_claim_support
    Dir.mktmpdir do |directory|
      assert_equal 'not_ported', TapetumVersionMatrix.inspect_version(directory, '1.17')[:state]
    end
  end

  def test_source_and_wrong_target_states_without_an_artifact
    success = Struct.new(:success?).new(true)
    Dir.mktmpdir do |directory|
      Open3.stub(:capture3, ["minecraftVersion=1.17\n", '', success]) do
        assert_equal 'source_only', TapetumVersionMatrix.inspect_version(directory, '1.17')[:state]
        assert_equal 'branch_target_mismatch', TapetumVersionMatrix.inspect_version(directory, '1.17.1')[:state]
      end
    end
  end

  def test_artifact_integrity_does_not_claim_game_acceptance
    with_artifacts(['tapetum.jar', 'tapetum-sources.jar']) do |directory|
      TapetumArtifact.stub(:verify, {game: '1.17'}) do
        assert_equal 'artifact_verified_game_untested',
          TapetumVersionMatrix.inspect_version(directory, '1.17')[:state]
      end
      TapetumArtifact.stub(:verify, {game: '1.17.1'}) do
        assert_raises(ArgumentError) { TapetumVersionMatrix.inspect_version(directory, '1.17') }
      end
    end
  end

  def test_duplicate_production_artifacts_are_rejected
    with_artifacts(['first.jar', 'second.jar']) do |directory|
      assert_raises(ArgumentError) { TapetumVersionMatrix.inspect_version(directory, '1.17') }
    end
  end

  def test_sources_alone_do_not_count_as_a_production_artifact
    with_artifacts(['tapetum-sources.jar']) do |directory|
      assert_equal 'source_only', TapetumVersionMatrix.inspect_version(directory, '1.17')[:state]
    end
  end

  private

  def with_artifacts(names)
    Dir.mktmpdir do |directory|
      artifacts = File.join(directory, 'build/version-artifacts/1.17')
      FileUtils.mkdir_p(artifacts)
      names.each { |name| File.write(File.join(artifacts, name), '') }
      Open3.stub(:capture3, ["minecraftVersion=1.17\n", '', Struct.new(:success?).new(true)]) do
        yield directory
      end
    end
  end
end
