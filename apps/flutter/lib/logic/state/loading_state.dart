abstract class LoadingState<T> {
  const LoadingState();

  factory LoadingState.loading() = Loading;
  factory LoadingState.empty() = Empty;
  factory LoadingState.success(T response) = Success<T>;
  factory LoadingState.error(String errMsg) = Error;
}

class Loading extends LoadingState<Never> {
  const Loading();
}

class Empty extends LoadingState<Never> {
  const Empty();
}

class Success<T> extends LoadingState<T> {
  final T response;
  final bool isPublic;
  const Success(this.response, {this.isPublic = false});
}

class Error extends LoadingState<Never> {
  final String errMsg;
  const Error(this.errMsg);
}
