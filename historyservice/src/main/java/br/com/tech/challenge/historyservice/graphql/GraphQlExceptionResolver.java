package br.com.tech.challenge.historyservice.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Traduz excecoes dos resolvers para erros tipados do GraphQL.
 *
 * Sem isto, tudo vira INTERNAL_ERROR com a mensagem substituida por um id opaco, e o cliente nao
 * descobre que enviou um argumento invalido. O que nao e tratado aqui continua mascarado de
 * proposito -- nao devolvemos detalhe de infraestrutura ao cliente.
 */
@Slf4j
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .build();
        }

        log.error("Erro nao tratado no resolver {}", env.getField().getName(), ex);
        return null;
    }
}
