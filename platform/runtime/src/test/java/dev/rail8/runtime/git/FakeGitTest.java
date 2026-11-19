package dev.rail8.runtime.git;

/** The contract of {@link PackageRepositories} on the fake. */
class FakeGitTest extends PackageRepositoriesContract {

    private FakeGit fake;

    @Override
    protected Source newSource() {
        fake = new FakeGit();
        return fake;
    }

    @Override
    protected Cache cache(Options options) {
        FakeGit.Cache cache = fake.cache(options.path(), options.ref(), options.deployed(), GitSettings.TIMEOUT_DEFAULT, options.maxBytes(),
                options.maxPackageBytes(), timer, options.named());
        return cache::open;
    }
}
